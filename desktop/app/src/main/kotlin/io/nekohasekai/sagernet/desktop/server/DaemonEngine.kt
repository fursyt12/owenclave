package io.nekohasekai.sagernet.desktop.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.desktop.BalancerSpec
import io.nekohasekai.sagernet.desktop.CoreRunner
import io.nekohasekai.sagernet.desktop.DesktopConfigBuilder
import io.nekohasekai.sagernet.desktop.DesktopSettings
import io.nekohasekai.sagernet.desktop.Profile
import io.nekohasekai.sagernet.desktop.RoutingRule
import io.nekohasekai.sagernet.desktop.ServerInboundOptions
import io.nekohasekai.sagernet.desktop.Subscription
import io.nekohasekai.sagernet.desktop.SubscriptionImporter
import io.nekohasekai.sagernet.desktop.hwidHeaders
import io.nekohasekai.sagernet.fmt.anytls.AnyTLSBean
import io.nekohasekai.sagernet.fmt.anytls.toUri
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.toUri
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.hysteria2.toUri
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.toUri
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.toUri
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.toUri
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.toUri
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.fmt.tuic5.Tuic5Bean
import io.nekohasekai.sagernet.fmt.tuic5.toUri
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.toUri
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.toConf
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runtime engine for the Dockerized / headless Mihomo-compatible Owenclave server.
 *
 * Supervises `owenclave-core` + external plugins (`naive`) with native `mixed` and
 * optional `tproxy` inbounds, evaluates Mihomo `proxy-groups` + `rule-providers` +
 * `rules`, persists manual profiles/subscriptions/selections, and exposes thread-safe
 * operations to [WebControllerServer].
 */
class DaemonEngine(val config: DaemonConfig) {

    data class LogRecord(
        val time: String,
        val type: String,
        val payload: String,
    )

    data class DelayRecord(
        val time: String,
        val delay: Int,
    )

    private val lock = Any()
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val stateFile = File(config.dataDir, "daemon-state.json")
    private val ruleCacheDir = File(config.dataDir, "rule-providers")
    private val subCacheDir = File(config.dataDir, "subscriptions-cache")

    private fun subCacheFile(subId: String): File {
        val safe = subId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(subCacheDir, "$safe.yaml")
    }

    private val logBuffer = ArrayDeque<LogRecord>(MAX_LOGS)
    private val delayHistory = LinkedHashMap<String, MutableList<DelayRecord>>()
    private val nodeHealth = LinkedHashMap<String, Boolean>()

    private val configProfiles = ArrayList<Profile>()
    private val manualProfiles = ArrayList<Profile>()
    private val subscriptionProfiles = ArrayList<Profile>()
    private val subscriptions = ArrayList<Subscription>()
    private val subscriptionUserAgents = LinkedHashMap<String, String>()
    private val subscriptionDocuments = LinkedHashMap<String, MihomoYamlSupport.ParsedMihomoDocument>()
    private var configDocument: MihomoYamlSupport.ParsedMihomoDocument? = null

    private val groupSelections = LinkedHashMap<String, String>()
    private val userRules = ArrayList<RoutingRule>()

    private var selectedProfileId: String? = null
    private var internalSocksPort: Int = 0
    private var tproxyDisabledFallback: Boolean = false
    private var lastAppliedCoreSignature: String? = null

    @Volatile
    var startedAtMillis: Long = 0L
        private set

    private val runner = CoreRunner(workDir = config.dataDir) { line ->
        observeCoreLine(line)
        recordLog(classifyLogLevel(line), line)
    }
    private var scheduler: ScheduledExecutorService? = null

    fun start() {
        synchronized(lock) {
            startedAtMillis = System.currentTimeMillis()
            recordLog("info", "starting Owenclave server ${BuildConfig.VERSION_NAME} (dataDir=${config.dataDir.absolutePath})")
            loadPersistedStateLocked()

            config.rawConfigText?.let { raw ->
                configDocument = MihomoYamlSupport.parseDocument(raw, subscriptionId = null)
                configProfiles.clear()
                configDocument?.profiles?.let { configProfiles.addAll(it) }
            }

            // Register seed subscriptions that aren't already tracked
            for (seed in config.seedSubscriptions) {
                val existing = subscriptions.firstOrNull { it.url == seed.url }
                if (existing == null) {
                    val sub = Subscription(name = seed.name, url = seed.url, sendHwid = seed.sendHwid)
                    subscriptions += sub
                    if (seed.userAgent.isNotBlank()) {
                        subscriptionUserAgents[sub.id] = seed.userAgent
                    }
                } else if (seed.userAgent.isNotBlank() && existing.id !in subscriptionUserAgents) {
                    subscriptionUserAgents[existing.id] = seed.userAgent
                }
            }
        }

        // Fetch subscriptions outside the main lock on boot if any exist
        val subsSnapshot = synchronized(lock) { subscriptions.toList() }
        for (sub in subsSnapshot) {
            runCatching { refreshSubscription(sub.id, reloadAfter = false) }
                .onFailure { recordLog("warning", "initial subscription fetch failed for '${sub.displayName}': ${it.message}") }
        }

        synchronized(lock) {
            ensureSelectionLocked()
            persistStateLocked()
            reloadCoreLocked()
        }

        if (config.autoUpdateMinutes > 0) {
            val exec = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "owenclave-sub-updater").apply { isDaemon = true }
            }
            val interval = config.autoUpdateMinutes.toLong().coerceAtLeast(5L)
            exec.scheduleAtFixedRate(
                { runCatching { refreshAllSubscriptions() } },
                interval,
                interval,
                TimeUnit.MINUTES,
            )
            scheduler = exec
        }
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        synchronized(lock) {
            lastAppliedCoreSignature = null
            runCatching { runner.stop() }
        }
    }

    // ------------------------------------------------------------------ queries

    fun allProfiles(): List<Profile> = synchronized(lock) { allProfilesLocked() }

    fun activeProfile(): Profile? = synchronized(lock) { activeProfileLocked() }

    fun subscriptionsList(): List<Subscription> = synchronized(lock) { subscriptions.toList() }

    fun recentLogs(limit: Int = 200): List<LogRecord> = synchronized(lock) {
        logBuffer.takeLast(limit.coerceIn(1, MAX_LOGS))
    }

    fun isCoreRunning(): Boolean = runner.running

    fun coreStartCount(): Int = runner.startCount

    fun corePid(): Long? = runner.corePid

    fun lastCoreError(): String? = runner.lastError

    data class GroupView(
        val name: String,
        val type: String,
        val all: List<String>,
        val now: String,
    )

    data class CompiledRuleView(
        val id: String,
        val name: String,
        val type: String,
        val payload: String,
        val proxy: String,
        val userDefined: Boolean,
    )

    fun groupViews(): List<GroupView> = synchronized(lock) { buildGroupViewsLocked() }

    fun compiledRuleViews(): List<CompiledRuleView> = synchronized(lock) {
        val views = ArrayList<CompiledRuleView>()
        for (r in userRules) {
            if (!r.enabled) continue
            views += CompiledRuleView(
                id = r.id,
                name = r.name,
                type = "USER-RULE",
                payload = r.summary,
                proxy = r.target.uppercase(),
                userDefined = true,
            )
        }
        val merged = mergedMihomoContextLocked()
        for ((idx, line) in merged.rawRules.withIndex()) {
            val parts = line.split(',').map { it.trim() }
            if (parts.size >= 3) {
                views += CompiledRuleView(
                    id = "mihomo-$idx",
                    name = line,
                    type = parts[0],
                    payload = parts[1],
                    proxy = parts[2],
                    userDefined = false,
                )
            } else if (parts.size == 2) {
                views += CompiledRuleView(
                    id = "mihomo-$idx",
                    name = line,
                    type = parts[0],
                    payload = "",
                    proxy = parts[1],
                    userDefined = false,
                )
            }
        }
        views
    }

    fun delayHistoryOf(name: String): List<DelayRecord> = synchronized(lock) {
        delayHistory[name]?.toList().orEmpty()
    }

    /** `true` if observatory probed the node alive, `false` if dead, `null` if not yet probed. */
    fun nodeHealthOf(profileId: String): Boolean? = synchronized(lock) {
        nodeHealth[profileId]
    }

    // --------------------------------------------------------------- mutations

    /**
     * Adds an HTTP or HTTPS proxy profile manually and reloads the core if it
     * becomes the selected profile.
     */
    fun addHttpProxy(
        tls: Boolean,
        host: String,
        port: Int,
        username: String = "",
        password: String = "",
        sni: String = "",
        allowInsecure: Boolean = false,
        udpOverTcp: Boolean = false,
        name: String = "",
        selectAfter: Boolean = true,
    ): Profile {
        val cleanHost = host.trim().removeSurrounding("[", "]")
        require(cleanHost.isNotEmpty()) { "host is required" }
        require(port in 1..65535) { "port must be in 1..65535" }

        val bean = HttpBean().apply {
            this.name = name.trim()
            serverAddress = cleanHost
            serverPort = port
            this.username = username
            this.password = password
            security = if (tls) "tls" else "none"
            this.sni = sni.trim()
            this.allowInsecure = allowInsecure
            this.connectUDP = udpOverTcp
            initializeDefaultValues()
        }
        val profile = Profile(name = bean.name, bean = bean)
        synchronized(lock) {
            val wasEmpty = allProfilesLocked().isEmpty()
            manualProfiles += profile
            if (selectAfter || wasEmpty || selectedProfileId == null) {
                selectedProfileId = profile.id
                groupSelections["GLOBAL"] = profile.displayName
            }
            persistStateLocked()
            reloadCoreLocked()
        }
        recordLog("info", "added ${profile.protocolName} proxy '${profile.displayName}' (${profile.address})")
        return profile
    }

    /**
     * Imports share links (`http://`, `https://`, `socks5://`, `socks+tls://`,
     * `vless://`, `vmess://`, `trojan://`, `ss://`, `ssr://`, `hy2://`, `tuic://`,
     * `anytls://`, `naive+https://`, `wg://`, `happ://crypt...`), Clash YAML,
     * V2Ray/sing-box JSON, or raw V2Ray config JSON.
     */
    fun importProfiles(text: String, nameHint: String = "Imported", keepAsRaw: Boolean = false): List<Profile> {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "input is empty" }
        val added = ArrayList<Profile>()

        if (keepAsRaw) {
            val profile = Profile(name = nameHint.ifBlank { "Custom config" }, customConfig = trimmed)
            synchronized(lock) {
                manualProfiles += profile
                if (selectedProfileId == null) selectedProfileId = profile.id
                persistStateLocked()
                reloadCoreLocked()
            }
            return listOf(profile)
        }

        val beans = SubscriptionImporter.parse(trimmed)
        if (beans.isEmpty() && trimmed.startsWith("{")) {
            val profile = Profile(name = nameHint.ifBlank { "Custom config" }, customConfig = trimmed)
            added += profile
        } else {
            beans.forEach { bean ->
                added += Profile(name = bean.name, bean = bean)
            }
        }
        require(added.isNotEmpty()) { "no supported proxy profiles recognised in input" }

        synchronized(lock) {
            val hadNone = allProfilesLocked().isEmpty()
            manualProfiles += added
            if (hadNone || selectedProfileId == null) {
                selectedProfileId = added.first().id
            }
            persistStateLocked()
            reloadCoreLocked()
        }
        recordLog("info", "imported ${added.size} profile(s)")
        return added
    }

    fun removeProfile(idOrName: String): Boolean = synchronized(lock) {
        val removed = manualProfiles.removeAll { it.id == idOrName || it.displayName == idOrName }
        if (removed) {
            ensureSelectionLocked()
            persistStateLocked()
            reloadCoreLocked()
        }
        removed
    }

    fun selectProfileByIdOrName(idOrName: String): Boolean = synchronized(lock) {
        if (idOrName.equals("DIRECT", ignoreCase = true)) {
            groupSelections["GLOBAL"] = "DIRECT"
            config.mode = "direct"
            persistStateLocked()
            reloadCoreLocked()
            return true
        }
        val target = allProfilesLocked().firstOrNull { it.id == idOrName || it.displayName == idOrName }
            ?: return false
        selectedProfileId = target.id
        groupSelections["GLOBAL"] = target.displayName
        if (config.mode == "direct") config.mode = "rule"
        persistStateLocked()
        reloadCoreLocked()
        true
    }

    /**
     * Mihomo `PUT /proxies/{groupName}` with `{"name": "<choice>"}`.
     */
    fun selectInGroup(groupName: String, choice: String): Boolean = synchronized(lock) {
        val views = buildGroupViewsLocked()
        val group = views.firstOrNull { it.name == groupName } ?: return false
        if (choice !in group.all) return false

        groupSelections[groupName] = choice
        val profilesByName = allProfilesLocked().associateBy { it.displayName }
        val merged = mergedMihomoContextLocked()

        if (groupName == "GLOBAL") {
            if (choice.equals("DIRECT", ignoreCase = true)) {
                config.mode = "direct"
            } else {
                val resolved = MihomoYamlSupport.resolveSelectedProfile(
                    choice,
                    profilesByName,
                    merged.groupsByName,
                    groupSelections,
                )
                if (resolved != null) {
                    selectedProfileId = resolved.id
                    if (config.mode == "direct") config.mode = "global"
                }
            }
        } else {
            // Re-resolve the root selector group (walking nested groups like
            // PROXY -> 🚫 Недоступные сайты -> 🎲 Любой доступный сервер -> naive-extra-0)
            // so switching any group in the active chain updates the default active profile too.
            val rootGroupName = views.firstOrNull { it.name.equals("PROXY", ignoreCase = true) }?.name
                ?: views.firstOrNull { it.name != "GLOBAL" }?.name
                ?: groupName
            val resolvedRoot = MihomoYamlSupport.resolveSelectedProfile(
                rootGroupName,
                profilesByName,
                merged.groupsByName,
                groupSelections,
            ) ?: MihomoYamlSupport.resolveSelectedProfile(
                choice,
                profilesByName,
                merged.groupsByName,
                groupSelections,
            )
            if (resolvedRoot != null) {
                selectedProfileId = resolvedRoot.id
            }
        }
        persistStateLocked()
        reloadCoreLocked()
        recordLog("info", "group '$groupName' -> '$choice'")
        true
    }

    fun addSubscription(
        name: String,
        url: String,
        userAgent: String = "",
        sendHwid: Boolean = false,
    ): Subscription {
        val cleanUrl = url.trim()
        require(cleanUrl.isNotEmpty()) { "subscription url is required" }
        val sub = synchronized(lock) {
            val s = Subscription(
                name = name.trim().ifBlank { "Subscription ${subscriptions.size + 1}" },
                url = cleanUrl,
                sendHwid = sendHwid,
            )
            subscriptions += s
            if (userAgent.isNotBlank()) subscriptionUserAgents[s.id] = userAgent.trim()
            persistStateLocked()
            s
        }
        refreshSubscription(sub.id, reloadAfter = true)
        return sub
    }

    fun refreshSubscription(idOrName: String, reloadAfter: Boolean = true): Subscription =
        refreshSubscriptionInternal(idOrName, reloadAfter).first

    private fun refreshSubscriptionInternal(idOrName: String, reloadAfter: Boolean): Pair<Subscription, Boolean> {
        val (sub, ua, socksPort) = synchronized(lock) {
            val found = subscriptions.firstOrNull { it.id == idOrName || it.displayName == idOrName }
                ?: throw IllegalArgumentException("subscription not found: $idOrName")
            val agent = subscriptionUserAgents[found.id]?.takeIf { it.isNotBlank() } ?: config.subscriptionUserAgent
            val port = internalSocksPort.takeIf { runner.running && it > 0 && activeProfileLocked() != null }
            Triple(found, agent, port)
        }

        val headers = DesktopSettings().hwidHeaders(sub.sendHwid)
        // Download directly first so bootstrap works even before upstream is connected,
        // or via running proxy if direct fails. Never touch running connections on failure.
        val body = try {
            runCatching {
                SubscriptionImporter.download(sub.url, socksPort = null, headers = headers, customUserAgent = ua)
            }.getOrElse { directErr ->
                if (socksPort != null) {
                    SubscriptionImporter.download(sub.url, socksPort = socksPort, headers = headers, customUserAgent = ua)
                } else {
                    throw directErr
                }
            }
        } catch (err: Throwable) {
            synchronized(lock) {
                sub.lastError = err.message ?: err.javaClass.simpleName
                persistStateLocked()
            }
            recordLog("warning", "subscription '${sub.displayName}' failed to update (${err.message}); keeping active session")
            throw err
        }

        val mihomoDoc = MihomoYamlSupport.parseDocument(body, subscriptionId = sub.id)
        val parsedProfiles = if (mihomoDoc != null && (mihomoDoc.profiles.isNotEmpty() || mihomoDoc.rawRules.isNotEmpty())) {
            mihomoDoc.profiles
        } else {
            runCatching { SubscriptionImporter.parse(body) }.getOrDefault(emptyList()).map { bean ->
                Profile(name = bean.name, bean = bean, subscriptionId = sub.id)
            }
        }
        val newDoc = mihomoDoc?.takeIf { it.groups.isNotEmpty() || it.rawRules.isNotEmpty() }

        var effectiveChanged = false
        var finalCount = 0
        synchronized(lock) {
            val existingForSub = subscriptionProfiles.filter { it.subscriptionId == sub.id }
            if (parsedProfiles.isEmpty() && (newDoc == null || existingForSub.isNotEmpty())) {
                val msg = if (existingForSub.isNotEmpty()) {
                    "subscription '${sub.displayName}' returned 0 proxy servers; keeping existing ${existingForSub.size} server(s)"
                } else {
                    "subscription '${sub.displayName}' returned no valid profiles or rules"
                }
                sub.lastError = msg
                persistStateLocked()
                recordLog("warning", "$msg; keeping active session")
                throw IllegalStateException(msg)
            }

            val (reconciledProfiles, profilesChanged) = Profile.reconcileProfiles(existingForSub, parsedProfiles)
            val oldDoc = subscriptionDocuments[sub.id]
            val docChanged = when {
                newDoc == null && oldDoc == null -> false
                newDoc != null && oldDoc != null -> !newDoc.hasSameRoutingSemantics(oldDoc)
                else -> true
            }

            if (profilesChanged) {
                val firstIdx = subscriptionProfiles.indexOfFirst { it.subscriptionId == sub.id }
                subscriptionProfiles.removeAll { it.subscriptionId == sub.id }
                if (firstIdx in 0..subscriptionProfiles.size) {
                    subscriptionProfiles.addAll(firstIdx, reconciledProfiles)
                } else {
                    subscriptionProfiles.addAll(reconciledProfiles)
                }
            }

            if (newDoc != null) {
                if (docChanged || profilesChanged || oldDoc == null) {
                    subscriptionDocuments[sub.id] = newDoc.copy(profiles = reconciledProfiles)
                }
                if (docChanged || !subCacheFile(sub.id).isFile) {
                    runCatching {
                        subCacheDir.mkdirs()
                        subCacheFile(sub.id).writeText(body)
                    }
                }
            } else if (oldDoc != null) {
                subscriptionDocuments.remove(sub.id)
                runCatching { subCacheFile(sub.id).delete() }
            }

            val prevSelected = selectedProfileId
            ensureSelectionLocked()
            val selectionChanged = selectedProfileId != prevSelected

            sub.lastUpdated = System.currentTimeMillis()
            sub.lastError = null
            sub.profileCount = reconciledProfiles.size
            finalCount = reconciledProfiles.size
            effectiveChanged = profilesChanged || docChanged || selectionChanged
            persistStateLocked()

            if (reloadAfter) {
                if (effectiveChanged || !runner.healthy) {
                    reloadCoreLocked()
                }
            }
        }
        if (effectiveChanged) {
            recordLog("info", "updated subscription '${sub.displayName}': $finalCount profile(s)")
        } else {
            recordLog("info", "subscription '${sub.displayName}' unchanged ($finalCount profile(s)); keeping active session")
        }
        return sub to effectiveChanged
    }

    fun refreshAllSubscriptions() {
        val ids = synchronized(lock) { subscriptions.map { it.id } }
        if (ids.isEmpty()) return
        var anyChanged = false
        for (id in ids) {
            runCatching {
                val (_, subChanged) = refreshSubscriptionInternal(id, reloadAfter = false)
                if (subChanged) anyChanged = true
            }
        }
        synchronized(lock) {
            if (anyChanged || !runner.healthy) {
                ensureSelectionLocked()
                persistStateLocked()
                reloadCoreLocked()
            }
        }
    }

    fun removeSubscription(idOrName: String): Boolean = synchronized(lock) {
        val sub = subscriptions.firstOrNull { it.id == idOrName || it.displayName == idOrName } ?: return false
        subscriptions.remove(sub)
        subscriptionUserAgents.remove(sub.id)
        subscriptionDocuments.remove(sub.id)
        runCatching { subCacheFile(sub.id).delete() }
        subscriptionProfiles.removeAll { it.subscriptionId == sub.id }
        ensureSelectionLocked()
        persistStateLocked()
        reloadCoreLocked()
        true
    }

    fun addUserRule(rule: RoutingRule): RoutingRule {
        require(rule.hasMatchers) { "rule needs at least one matcher (domains, ip, port, or network)" }
        synchronized(lock) {
            userRules += rule
            persistStateLocked()
            reloadCoreLocked()
        }
        return rule
    }

    fun removeUserRule(id: String): Boolean = synchronized(lock) {
        val removed = userRules.removeAll { it.id == id }
        if (removed) {
            persistStateLocked()
            reloadCoreLocked()
        }
        removed
    }

    fun patchConfig(patch: JsonObject) {
        synchronized(lock) {
            patch.get("mode")?.takeIf { !it.isJsonNull }?.asString?.lowercase()?.let { m ->
                if (m in setOf("rule", "global", "direct")) config.mode = m
            }
            patch.get("log-level")?.takeIf { !it.isJsonNull }?.asString?.lowercase()?.let { lvl ->
                config.logLevel = lvl
            }
            patch.get("allow-lan")?.takeIf { !it.isJsonNull }?.asBoolean?.let { allow ->
                config.allowLan = allow
            }
            patch.get("bind-address")?.takeIf { !it.isJsonNull }?.asString?.let { addr ->
                config.bindAddress = addr.trim().ifEmpty { "0.0.0.0" }
            }
            patch.get("mixed-port")?.takeIf { !it.isJsonNull }?.asInt?.let { port ->
                if (port in 0..65535) config.mixedPort = port
            }
            patch.get("socks-port")?.takeIf { !it.isJsonNull }?.asInt?.let { port ->
                if (port in 0..65535) config.socksPort = port
            }
            patch.get("port")?.takeIf { !it.isJsonNull }?.asInt?.let { port ->
                if (port in 0..65535) config.httpPort = port
            }
            patch.get("tproxy-port")?.takeIf { !it.isJsonNull }?.asInt?.let { port ->
                if (port in 0..65535) {
                    config.tproxyPort = port
                    tproxyDisabledFallback = false
                }
            }
            patch.get("interface-name")?.takeIf { !it.isJsonNull }?.asString?.let { iface ->
                config.interfaceName = iface.trim()
            }
            patch.get("ipv6")?.takeIf { !it.isJsonNull }?.asBoolean?.let { v6 ->
                config.ipv6 = v6
            }
            patch.get("fallback-to-direct")?.takeIf { !it.isJsonNull }?.asBoolean?.let { fb ->
                config.fallbackToDirect = fb
            }
            patch.get("health-check-interval")?.takeIf { !it.isJsonNull }?.asInt?.let { sec ->
                if (sec in 0..3600) config.healthCheckInterval = sec
            }
            persistStateLocked()
            reloadCoreLocked()
        }
    }

    fun reload(forceRestart: Boolean = true) {
        synchronized(lock) {
            config.configFile?.takeIf { it.isFile }?.let { file ->
                runCatching { file.readText() }.getOrNull()?.let { raw ->
                    val parsed = MihomoYamlSupport.parseDocument(raw, subscriptionId = null)
                    val (reconciled, _) = Profile.reconcileProfiles(configProfiles, parsed?.profiles.orEmpty())
                    configDocument = parsed?.copy(profiles = reconciled)
                    configProfiles.clear()
                    configProfiles.addAll(reconciled)
                }
            }
            ensureSelectionLocked()
            persistStateLocked()
            reloadCoreLocked(forceRestart = forceRestart)
        }
    }

    /**
     * Measures HTTP latency (ms) through the running core's local SOCKS inbound.
     */
    fun measureDelay(
        targetName: String,
        url: String = DEFAULT_DELAY_URL,
        timeoutMs: Int = 5_000,
    ): Int {
        val socks = synchronized(lock) { internalSocksPort }
        if (!runner.running || socks <= 0) {
            throw IllegalStateException("core is not running")
        }
        val boundTimeout = timeoutMs.coerceIn(500, 30_000)
        val startNs = System.nanoTime()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks))
        val conn = (URI.create(url).toURL().openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = boundTimeout
            readTimeout = boundTimeout
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Owenclave/${BuildConfig.VERSION_NAME}")
        }
        val code = try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
        if (code !in 200..399) {
            throw IllegalStateException("HTTP $code from $url")
        }
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
        val stamp = Instant.now().toString()
        synchronized(lock) {
            val list = delayHistory.getOrPut(targetName) { ArrayList() }
            list += DelayRecord(time = stamp, delay = elapsedMs)
            while (list.size > 20) list.removeAt(0)
        }
        return elapsedMs
    }

    // --------------------------------------------------------- core supervision

    private fun reloadCoreLocked(forceRestart: Boolean = false) {
        val active = activeProfileLocked()
        // When no profile exists yet, use a synthetic freedom-compatible placeholder in DIRECT mode
        // so `mixed-port` and health probes remain online.
        val effectiveProfile = active ?: Profile(
            id = "direct-placeholder",
            name = "DIRECT",
            bean = SOCKSBean().apply {
                serverAddress = "127.0.0.1"
                serverPort = 1080
                protocol = SOCKSBean.PROTOCOL_SOCKS5
                initializeDefaultValues()
            },
        )
        val forceDirect = active == null || config.mode == "direct"

        val balancersMap = LinkedHashMap<String, BalancerSpec>()
        val compiledRules = if (config.mode == "rule" && !forceDirect) {
            buildEffectiveRulesLocked(balancersMap)
        } else {
            emptyList()
        }
        val primaryBalancer = if (!forceDirect) {
            resolvePrimaryBalancerLocked(effectiveProfile)
        } else {
            null
        }
        val allBalancers = balancersMap.values.toList()

        val routeMode = when {
            forceDirect -> DesktopSettings.ROUTE_DIRECT
            config.mode == "global" -> DesktopSettings.ROUTE_GLOBAL
            else -> DesktopSettings.ROUTE_RULE
        }

        val exposeSocksOnLan = config.allowLan && config.effectiveListenAddress() == "0.0.0.0" && config.socksPort > 0
        val requestedTproxy = config.tproxyPort.takeIf { it > 0 && !tproxyDisabledFallback }
        val inbounds = ServerInboundOptions(
            mixedPort = config.mixedPort.takeIf { it > 0 },
            bindAddress = config.effectiveListenAddress(),
            tproxyPort = requestedTproxy,
        )
        val allProfiles = allProfilesLocked()
        val outIface = config.interfaceName.takeIf { it.isNotBlank() }

        val stateSignature = buildCoreStateSignature(
            effectiveProfile = effectiveProfile,
            allProfiles = allProfiles,
            compiledRules = compiledRules,
            primaryBalancer = primaryBalancer,
            allBalancers = allBalancers,
            routeMode = routeMode,
            exposeSocksOnLan = exposeSocksOnLan,
            inbounds = inbounds,
            outIface = outIface,
        )
        if (!forceRestart && runner.healthy && stateSignature == lastAppliedCoreSignature) {
            recordLog("info", "core configuration unchanged; keeping active session")
            return
        }

        val localSocksPort = if (config.socksPort > 0 && (!config.allowLan || config.effectiveListenAddress() in setOf("0.0.0.0", "127.0.0.1"))) {
            config.socksPort
        } else {
            CoreRunner.freePort()
        }
        internalSocksPort = localSocksPort

        var desktopSettings = DesktopSettings(
            socksPort = localSocksPort,
            httpPort = config.httpPort.takeIf { it > 0 } ?: CoreRunner.freePort(),
            bypassPrivateNetworks = true,
            logLevel = config.toCoreLogLevel(),
            routeMode = routeMode,
        ).withValue(Key.REQUIRE_SOCKS, "true")
            .withValue(Key.REQUIRE_HTTP, if (config.httpPort > 0) "true" else "false")
            .withValue(Key.ALLOW_ACCESS, if (exposeSocksOnLan) "true" else "false")

        if (!config.ipv6) {
            desktopSettings = desktopSettings
                .withValue(Key.REMOTE_DNS_QUERY_STRATEGY, "UseIPv4")
                .withValue(Key.DIRECT_DNS_QUERY_STRATEGY, "UseIPv4")
        }

        try {
            runner.start(
                profile = effectiveProfile,
                settings = desktopSettings,
                rules = compiledRules,
                bindInterfaceOverride = outIface,
                serverInbounds = inbounds,
                extraProfiles = allProfiles,
                balancers = allBalancers,
                primaryBalancer = primaryBalancer,
            )
            lastAppliedCoreSignature = stateSignature
            recordLog(
                "info",
                "core active: mode=${if (forceDirect) "direct" else config.mode}, " +
                    "profile='${effectiveProfile.displayName}' (${effectiveProfile.protocolName}), " +
                    (if (primaryBalancer != null) {
                        "balancer='${primaryBalancer.id}' (${primaryBalancer.profileIds.size} server(s), fallbackToDirect=${primaryBalancer.fallbackToDirect}), "
                    } else {
                        ""
                    }) +
                    "mixed=${config.effectiveListenAddress()}:${config.mixedPort}, " +
                    "rules=${compiledRules.size}" +
                    (if (requestedTproxy != null) ", tproxy=${config.effectiveListenAddress()}:$requestedTproxy" else "") +
                    (if (outIface != null) ", interface=$outIface" else ""),
            )
        } catch (e: Exception) {
            if (requestedTproxy != null) {
                recordLog(
                    "warning",
                    "tproxy-port $requestedTproxy could not start (${e.message}); retrying without TProxy inbound",
                )
                tproxyDisabledFallback = true
                val fallbackInbounds = inbounds.copy(tproxyPort = null)
                runner.start(
                    profile = effectiveProfile,
                    settings = desktopSettings,
                    rules = compiledRules,
                    bindInterfaceOverride = outIface,
                    serverInbounds = fallbackInbounds,
                    extraProfiles = allProfiles,
                    balancers = allBalancers,
                    primaryBalancer = primaryBalancer,
                )
                lastAppliedCoreSignature = buildCoreStateSignature(
                    effectiveProfile = effectiveProfile,
                    allProfiles = allProfiles,
                    compiledRules = compiledRules,
                    primaryBalancer = primaryBalancer,
                    allBalancers = allBalancers,
                    routeMode = routeMode,
                    exposeSocksOnLan = exposeSocksOnLan,
                    inbounds = fallbackInbounds,
                    outIface = outIface,
                )
            } else {
                recordLog("error", "failed to start core: ${e.message}")
                throw e
            }
        }
    }

    private fun buildCoreStateSignature(
        effectiveProfile: Profile,
        allProfiles: List<Profile>,
        compiledRules: List<RoutingRule>,
        primaryBalancer: BalancerSpec?,
        allBalancers: List<BalancerSpec>,
        routeMode: String,
        exposeSocksOnLan: Boolean,
        inbounds: ServerInboundOptions,
        outIface: String?,
    ): String = buildString(1024) {
        append("mode=").append(routeMode)
        append("|log=").append(config.toCoreLogLevel())
        append("|ipv6=").append(config.ipv6)
        append("|socks=").append(config.socksPort)
        append("|http=").append(config.httpPort)
        append("|lanSocks=").append(exposeSocksOnLan)
        append("|inbounds=").append(inbounds)
        append("|iface=").append(outIface.orEmpty())
        append("|active=").append(effectiveProfile.id).append(':').append(effectiveProfile.contentSignature())
        append("|primaryBalancer=").append(primaryBalancer)
        append("|balancers=").append(allBalancers)
        append("|profiles=")
        for (p in allProfiles) {
            append(p.id).append(':').append(p.contentSignature()).append(';')
        }
        append("|rules=")
        for (r in compiledRules) {
            append(r.enabled).append('|')
                .append(r.target).append('|')
                .append(r.domains).append('|')
                .append(r.ip).append('|')
                .append(r.port).append('|')
                .append(r.network).append('|')
                .append(r.protocol).append(';')
        }
    }

    private data class MergedMihomoContext(
        val groups: List<MihomoYamlSupport.ProxyGroupSpec>,
        val groupsByName: Map<String, MihomoYamlSupport.ProxyGroupSpec>,
        val virtualDirect: Set<String>,
        val virtualReject: Set<String>,
        val virtualDns: Set<String>,
        val ruleProviders: Map<String, MihomoYamlSupport.RuleProviderSpec>,
        val rawRules: List<String>,
    )

    private fun mergedMihomoContextLocked(): MergedMihomoContext {
        val docs = ArrayList<MihomoYamlSupport.ParsedMihomoDocument>()
        configDocument?.let { docs += it }
        subscriptionDocuments.values.forEach { docs += it }

        val groupsByName = LinkedHashMap<String, MihomoYamlSupport.ProxyGroupSpec>()
        val virtualDirect = LinkedHashSet<String>()
        val virtualReject = LinkedHashSet<String>()
        val virtualDns = LinkedHashSet<String>()
        val ruleProviders = LinkedHashMap<String, MihomoYamlSupport.RuleProviderSpec>()
        val rawRules = ArrayList<String>()

        // Seed subscription groups first so overriding a group by name in `config.yaml`
        // preserves the subscription's group display order while letting `config.yaml` win.
        for (subDoc in subscriptionDocuments.values) {
            subDoc.groups.forEach { g ->
                if (g.name !in groupsByName) groupsByName[g.name] = g
            }
        }
        configDocument?.groups?.forEach { g ->
            groupsByName[g.name] = g
        }

        for (doc in docs) {
            virtualDirect += doc.virtualDirectNames
            virtualReject += doc.virtualRejectNames
            virtualDns += doc.virtualDnsNames
            doc.ruleProviders.forEach { (k, v) ->
                if (k !in ruleProviders) ruleProviders[k] = v
            }
            rawRules += doc.rawRules
        }

        return MergedMihomoContext(
            groups = groupsByName.values.toList(),
            groupsByName = groupsByName,
            virtualDirect = virtualDirect,
            virtualReject = virtualReject,
            virtualDns = virtualDns,
            ruleProviders = ruleProviders,
            rawRules = rawRules,
        )
    }

    private fun resolvePrimaryBalancerLocked(effectiveProfile: Profile): BalancerSpec? {
        val allProfiles = allProfilesLocked()
        if (allProfiles.isEmpty()) return null
        val profilesByName = allProfiles.associateBy { it.displayName }
        val merged = mergedMihomoContextLocked()

        if (merged.groups.isNotEmpty()) {
            val rootTarget = groupSelections["GLOBAL"]
                ?.takeIf { it.isNotBlank() && !it.equals("DIRECT", ignoreCase = true) }
                ?: merged.groups.firstOrNull { it.name.equals("PROXY", ignoreCase = true) }?.name
                ?: merged.groups.first().name
            val resolved = MihomoYamlSupport.resolveBalancerSpec(
                targetName = rootTarget,
                profilesByName = profilesByName,
                groupsByName = merged.groupsByName,
                groupSelections = groupSelections,
                virtualDirect = merged.virtualDirect,
                virtualReject = merged.virtualReject,
                defaultFallbackToDirect = config.fallbackToDirect,
                healthCheckIntervalCap = config.healthCheckInterval,
            )
            if (resolved != null) return resolved
        }

        if (config.fallbackToDirect && effectiveProfile.bean != null) {
            val interval = (if (config.healthCheckInterval > 0) config.healthCheckInterval else 15).coerceIn(1, 3600)
            return BalancerSpec(
                id = "primary-fb:${effectiveProfile.id}",
                profileIds = listOf(effectiveProfile.id),
                strategy = "leastPing",
                stickyChoice = false,
                probeIntervalSec = interval,
                fallbackToDirect = true,
            )
        }
        return null
    }

    private fun buildEffectiveRulesLocked(balancersOut: MutableMap<String, BalancerSpec>): List<RoutingRule> {
        val rules = ArrayList<RoutingRule>()
        userRules.filter { it.enabled && it.hasMatchers }.forEach { rules += it }
        val merged = mergedMihomoContextLocked()
        if (merged.rawRules.isNotEmpty()) {
            val profilesByName = allProfilesLocked().associateBy { it.displayName }
            rules += MihomoYamlSupport.compileRules(
                rawRules = merged.rawRules,
                ruleProviders = merged.ruleProviders,
                groupsByName = merged.groupsByName,
                groupSelections = groupSelections,
                virtualDirect = merged.virtualDirect,
                virtualReject = merged.virtualReject,
                virtualDns = merged.virtualDns,
                cacheDir = ruleCacheDir,
                configBaseDir = config.configFile?.parentFile,
                socksPort = null,
                profilesByName = profilesByName,
                balancersOut = balancersOut,
                defaultFallbackToDirect = config.fallbackToDirect,
                healthCheckIntervalCap = config.healthCheckInterval,
                log = { msg -> recordLog("info", msg) },
            )
        }
        return rules
    }

    private fun buildGroupViewsLocked(): List<GroupView> {
        val profiles = allProfilesLocked()
        val profileNames = profiles.map { it.displayName }
        val profilesByName = profiles.associateBy { it.displayName }
        val merged = mergedMihomoContextLocked()
        val views = ArrayList<GroupView>()

        for (spec in merged.groups) {
            val expanded = MihomoYamlSupport.expandGroupChoices(spec, profileNames, merged.virtualDirect)
            val choices = expanded.ifEmpty { profileNames + "DIRECT" }
            val now = if (spec.isAutoBalancer) {
                val memberProfiles = choices.mapNotNull { profilesByName[it] }
                val aliveMembers = memberProfiles.filter { nodeHealth[it.id] == true }
                when {
                    aliveMembers.isNotEmpty() -> {
                        aliveMembers.minByOrNull { p ->
                            delayHistory[p.displayName]?.lastOrNull()?.delay ?: Int.MAX_VALUE
                        }?.displayName ?: aliveMembers.first().displayName
                    }
                    memberProfiles.isNotEmpty() && memberProfiles.all { nodeHealth[it.id] == false } &&
                        (spec.fallbackToDirect || config.fallbackToDirect) && "DIRECT" in choices -> "DIRECT"
                    else -> groupSelections[spec.name]?.takeIf { it in choices } ?: choices.firstOrNull().orEmpty()
                }
            } else {
                groupSelections[spec.name]?.takeIf { it in choices }
                    ?: choices.firstOrNull().orEmpty()
            }
            views += GroupView(
                name = spec.name,
                type = when (spec.type) {
                    "url-test" -> "URLTest"
                    "fallback" -> "Fallback"
                    "load-balance" -> "LoadBalance"
                    else -> "Selector"
                },
                all = choices,
                now = now,
            )
        }

        if (views.none { it.name.equals("PROXY", ignoreCase = true) } && profileNames.isNotEmpty()) {
            val choices = profileNames + listOf("DIRECT")
            val activeName = activeProfileLocked()?.displayName ?: choices.first()
            val now = groupSelections["PROXY"]?.takeIf { it in choices } ?: activeName
            views.add(0, GroupView(name = "PROXY", type = "Selector", all = choices, now = now))
        }

        val globalChoices = buildList {
            views.filter { it.name != "GLOBAL" }.forEach { add(it.name) }
            profileNames.forEach { if (it !in this) add(it) }
            if ("DIRECT" !in this) add("DIRECT")
            if ("REJECT" !in this) add("REJECT")
        }
        val activeName = if (config.mode == "direct") {
            "DIRECT"
        } else {
            groupSelections["GLOBAL"]?.takeIf { it in globalChoices }
                ?: activeProfileLocked()?.displayName
                ?: globalChoices.firstOrNull().orEmpty()
        }
        views += GroupView(name = "GLOBAL", type = "Selector", all = globalChoices, now = activeName)
        return views
    }

    private fun allProfilesLocked(): List<Profile> {
        val all = ArrayList<Profile>(configProfiles.size + subscriptionProfiles.size + manualProfiles.size)
        all.addAll(configProfiles)
        all.addAll(subscriptionProfiles)
        all.addAll(manualProfiles)
        return all
    }

    private fun activeProfileLocked(): Profile? {
        val profiles = allProfilesLocked()
        if (profiles.isEmpty()) return null
        selectedProfileId?.let { id ->
            profiles.firstOrNull { it.id == id }?.let { return it }
        }
        return profiles.first()
    }

    private fun ensureSelectionLocked() {
        val profiles = allProfilesLocked()
        if (profiles.isEmpty()) {
            selectedProfileId = null
            return
        }
        if (selectedProfileId == null || profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = profiles.first().id
        }
    }

    // -------------------------------------------------------------- persistence

    private fun persistStateLocked() {
        runCatching {
            val root = JsonObject().apply {
                selectedProfileId?.let { addProperty("selectedProfileId", it) }
                addProperty("mode", config.mode)
                addProperty("logLevel", config.logLevel)
                addProperty("allowLan", config.allowLan)
                addProperty("bindAddress", config.bindAddress)
                addProperty("mixedPort", config.mixedPort)
                addProperty("socksPort", config.socksPort)
                addProperty("httpPort", config.httpPort)
                addProperty("tproxyPort", config.tproxyPort)
                addProperty("interfaceName", config.interfaceName)
                addProperty("ipv6", config.ipv6)

                add("manualProfiles", JsonArray().apply {
                    manualProfiles.forEach { add(it.toJson()) }
                })
                add("subscriptions", JsonArray().apply {
                    subscriptions.forEach { sub ->
                        val obj = sub.toJson()
                        subscriptionUserAgents[sub.id]?.let { obj.addProperty("userAgent", it) }
                        add(obj)
                    }
                })
                add("subscriptionProfiles", JsonArray().apply {
                    subscriptionProfiles.forEach { add(it.toJson()) }
                })
                add("groupSelections", JsonObject().apply {
                    groupSelections.forEach { (k, v) -> addProperty(k, v) }
                })
                add("userRules", JsonArray().apply {
                    userRules.forEach { add(it.toJson()) }
                })
            }
            config.dataDir.mkdirs()
            val tmp = File(config.dataDir, "daemon-state.json.tmp")
            tmp.writeText(gson.toJson(root))
            if (!tmp.renameTo(stateFile)) {
                tmp.copyTo(stateFile, overwrite = true)
                tmp.delete()
            }
        }.onFailure { err ->
            recordLog("warning", "could not persist daemon state: ${err.message}")
        }
    }

    private fun loadPersistedStateLocked() {
        if (!stateFile.isFile) return
        runCatching {
            val root = JsonParser.parseString(stateFile.readText()).asJsonObject
            selectedProfileId = root.get("selectedProfileId")?.takeIf { !it.isJsonNull }?.asString

            manualProfiles.clear()
            root.getAsJsonArray("manualProfiles")?.forEach { el ->
                if (el.isJsonObject) manualProfiles += Profile.fromJson(el.asJsonObject)
            }

            subscriptions.clear()
            subscriptionUserAgents.clear()
            root.getAsJsonArray("subscriptions")?.forEach { el ->
                if (el.isJsonObject) {
                    val obj = el.asJsonObject
                    val sub = Subscription.fromJson(obj)
                    subscriptions += sub
                    obj.get("userAgent")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
                        subscriptionUserAgents[sub.id] = it
                    }
                    subCacheFile(sub.id).takeIf { it.isFile }?.let { cached ->
                        runCatching { MihomoYamlSupport.parseDocument(cached.readText(), subscriptionId = sub.id) }
                            .getOrNull()
                            ?.takeIf { it.groups.isNotEmpty() || it.rawRules.isNotEmpty() }
                            ?.let { subscriptionDocuments[sub.id] = it }
                    }
                }
            }

            subscriptionProfiles.clear()
            root.getAsJsonArray("subscriptionProfiles")?.forEach { el ->
                if (el.isJsonObject) subscriptionProfiles += Profile.fromJson(el.asJsonObject)
            }

            groupSelections.clear()
            root.getAsJsonObject("groupSelections")?.entrySet()?.forEach { (k, v) ->
                if (v.isJsonPrimitive) groupSelections[k] = v.asString
            }

            userRules.clear()
            root.getAsJsonArray("userRules")?.forEach { el ->
                if (el.isJsonObject) userRules += RoutingRule.fromJson(el.asJsonObject)
            }
        }.onFailure { err ->
            recordLog("warning", "failed to read persisted daemon-state.json: ${err.message}")
        }
    }

    internal fun observeCoreLine(line: String) {
        if ("app/observatory:" !in line) return
        OBSERVATORY_ALIVE_REGEX.find(line)?.let { m ->
            val entityId = m.groupValues[1].toLongOrNull() ?: return
            val secs = m.groupValues[2].toDoubleOrNull() ?: return
            val delayMs = (secs * 1000.0).toInt().coerceAtLeast(1)
            val stamp = Instant.now().toString()
            synchronized(lock) {
                val target = allProfilesLocked().firstOrNull { DesktopConfigBuilder.entityIdOf(it.id) == entityId }
                if (target != null) {
                    nodeHealth[target.id] = true
                    val list = delayHistory.getOrPut(target.displayName) { ArrayList() }
                    list += DelayRecord(time = stamp, delay = delayMs)
                    while (list.size > 20) list.removeAt(0)
                }
            }
            return
        }
        OBSERVATORY_DEAD_REGEX.find(line)?.let { m ->
            val entityId = m.groupValues[1].toLongOrNull() ?: return
            synchronized(lock) {
                val target = allProfilesLocked().firstOrNull { DesktopConfigBuilder.entityIdOf(it.id) == entityId }
                if (target != null) {
                    nodeHealth[target.id] = false
                }
            }
        }
    }

    private fun recordLog(type: String, payload: String) {
        val stamp = Instant.now().toString()
        val entry = LogRecord(time = stamp, type = type, payload = payload)
        println("[$stamp] [$type] $payload")
        synchronized(lock) {
            if (logBuffer.size >= MAX_LOGS) logBuffer.removeFirst()
            logBuffer.addLast(entry)
        }
    }

    private fun classifyLogLevel(line: String): String {
        val lower = line.lowercase()
        return when {
            "[error]" in lower || "error:" in lower || "failed" in lower -> "error"
            "[warning]" in lower || "warn" in lower -> "warning"
            "[debug]" in lower -> "debug"
            else -> "info"
        }
    }

    companion object {
        private const val MAX_LOGS = 500
        const val DEFAULT_DELAY_URL = "https://www.gstatic.com/generate_204"
        private val OBSERVATORY_ALIVE_REGEX = Regex("""app/observatory: the outbound proxy-global-(\d+) is alive:([0-9.]+)""")
        private val OBSERVATORY_DEAD_REGEX = Regex("""app/observatory: the outbound proxy-global-(\d+) is dead""")

        /** Exports a [Profile] to a standard share URI where supported by upstream formatters. */
        fun shareUriOf(profile: Profile): String? = runCatching {
            when (val b = profile.bean) {
                is HttpBean -> b.toUri()
                is SOCKSBean -> b.toUri()
                is ShadowsocksBean -> b.toUri()
                is ShadowsocksRBean -> b.toUri()
                is StandardV2RayBean -> b.toUri()
                is Hysteria2Bean -> b.toUri()
                is Tuic5Bean -> b.toUri()
                is AnyTLSBean -> b.toUri()
                is JuicityBean -> b.toUri()
                is NaiveBean -> b.toUri()
                is WireGuardBean -> b.toConf()
                else -> null
            }
        }.getOrNull()

        /** Maps an Owenclave protocol name to the Mihomo `/proxies` `type` label. */
        fun mihomoTypeOf(profile: Profile): String = when (profile.protocolName) {
            "HTTP" -> "Http"
            "HTTPS" -> "Http"
            "SOCKS4", "SOCKS4A", "SOCKS5" -> "Socks5"
            "Shadowsocks" -> "Shadowsocks"
            "ShadowsocksR" -> "ShadowsocksR"
            "VMess" -> "Vmess"
            "VLESS" -> "Vless"
            "Trojan" -> "Trojan"
            "Hysteria2" -> "Hysteria2"
            "TUIC" -> "Tuic"
            "AnyTLS" -> "AnyTLS"
            "WireGuard" -> "WireGuard"
            "SSH" -> "Ssh"
            "Snell" -> "Snell"
            "Mieru" -> "Mieru"
            else -> profile.protocolName
        }
    }

}
