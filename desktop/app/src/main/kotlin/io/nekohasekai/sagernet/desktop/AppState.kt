package io.nekohasekai.sagernet.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sections of the main window, modelled after the Android app's screens. */
enum class Section(val label: String, val hint: String) {
    PROFILES("Profiles", "Import or pick a profile and connect"),
    SUBSCRIPTIONS("Subscriptions", "Subscription URLs, refresh and HWID reporting"),
    RULES("Rules", "Routing rules, the first match wins"),
    SETTINGS("Settings", "The Android global settings one to one, plus the desktop-only options"),
    LOG("Log", "Core and plugin output"),
}

/**
 * UI state and operations of the desktop client.
 *
 * All blocking work (process handling, HTTP) runs on [Dispatchers.IO]; the
 * observable state is only touched from the main dispatcher.
 */
class AppState(private val scope: CoroutineScope) {

    private val store = ProfileStore().apply { load() }

    val profiles = mutableStateListOf<Profile>().apply { addAll(store.profiles) }

    val subscriptions = mutableStateListOf<Subscription>().apply { addAll(store.subscriptions) }

    val rules = mutableStateListOf<RoutingRule>().apply { addAll(store.rules) }

    var settings by mutableStateOf(store.settings)
        private set

    var section by mutableStateOf(initialSection())
        private set

    var selectedProfileId by mutableStateOf(store.settings.selectedProfileId)
        private set

    var connectedProfileId by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf("Ready")
        private set

    val logs = mutableStateListOf<String>()

    val runtimeLabel: String
        get() = buildString {
            append(DesktopRuntime.platformTag)
            if (DesktopRuntime.coreBinary() == null) append(" | core missing")
            if (DesktopRuntime.naiveBinary() == null) append(" | naive plugin missing")
            if (DesktopRuntime.olcrtcBinary() == null) append(" | olcrtc plugin missing")
        }

    private val runner = CoreRunner { line -> appendLog(line) }

    init {
        Logs.sink = { line -> appendLog("owenclave: $line") }
        appendLog("Owenclave desktop ${io.nekohasekai.sagernet.BuildConfig.VERSION_NAME} (${DesktopRuntime.platformTag})")
        appendLog("data directory: ${DesktopRuntime.dataDir.absolutePath}")
        appendLog("core binary: ${DesktopRuntime.coreBinary()?.absolutePath ?: "not found"}")
        appendLog("naive binary: ${DesktopRuntime.naiveBinary()?.absolutePath ?: "not found"}")
        // The Android `isAutoConnect` row ("restore the previous connection status")
        // maps to connecting the selected profile when the desktop client starts.
        if (store.settings.value(Key.PERSIST_ACROSS_REBOOT) == "true" && selectedProfile != null) {
            appendLog("auto connect: ${selectedProfile?.displayName}")
            connect()
        }
    }

    val selectedProfile: Profile?
        get() = profiles.firstOrNull { it.id == selectedProfileId }

    val isConnected: Boolean get() = connectedProfileId != null

    fun show(section: Section) {
        this.section = section
    }

    fun select(profile: Profile) {
        selectedProfileId = profile.id
        persist()
    }

    fun updateSettings(transform: (DesktopSettings) -> DesktopSettings) {
        settings = transform(settings)
        persist()
    }

    /** Stores one Android global preference key (the Settings catalog). */
    fun setPreference(key: String, value: String) {
        settings = settings.withValue(key, value)
        persist()
    }

    fun resetHwid() {
        settings = settings.copy(hwidValue = java.util.UUID.randomUUID().toString().replace("-", ""))
        persist()
        status = "Device HWID regenerated"
        appendLog("device HWID regenerated: ${settings.hwidValue}")
    }

    fun subscriptionOf(profile: Profile): Subscription? =
        profile.subscriptionId?.let { id -> subscriptions.firstOrNull { it.id == id } }

    // ---------------------------------------------------------------- profiles

    fun importText(text: String, source: String) {
        if (text.isBlank()) {
            status = "Nothing to import"
            return
        }
        scope.launch {
            busy = true
            status = "Importing $source ..."
            val beans = withContext(Dispatchers.IO) { runCatching { SubscriptionImporter.parse(text) } }
            beans.onSuccess { parsed ->
                if (parsed.isEmpty()) {
                    if (text.trimStart().startsWith("{")) {
                        profiles.add(Profile(name = source, customConfig = text))
                        status = "Imported raw config as a custom profile"
                    } else {
                        status = "Nothing recognised in $source"
                    }
                } else {
                    parsed.forEach { bean -> profiles.add(Profile(name = bean.name, bean = bean)) }
                    status = "Imported ${parsed.size} profile(s) from $source"
                    if (selectedProfileId == null) selectedProfileId = profiles.firstOrNull()?.id
                }
                persist()
            }.onFailure {
                status = "Import failed: ${SubscriptionImporter.describe(it)}"
                appendLog("import failed: ${SubscriptionImporter.describe(it)}")
            }
            busy = false
        }
    }

    fun importFile(file: File) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { file.readText() } }
            text.onSuccess { importText(it, file.name) }
                .onFailure { status = "Cannot read ${file.name}: ${it.message}" }
        }
    }

    fun addCustomConfig(text: String, name: String) {
        profiles.add(Profile(name = name.ifBlank { "Custom config" }, customConfig = text))
        selectedProfileId = profiles.lastOrNull()?.id
        persist()
        status = "Added custom config"
    }

    fun remove(profile: Profile) {
        if (connectedProfileId == profile.id) disconnect()
        profiles.remove(profile)
        if (selectedProfileId == profile.id) selectedProfileId = profiles.firstOrNull()?.id
        persist()
        status = "Removed ${profile.displayName}"
    }

    fun connect(profile: Profile? = null) {
        val target = profile ?: selectedProfile ?: return
        scope.launch {
            busy = true
            status = "Connecting to ${target.displayName} ..."
            val activeRules = rules.toList()
            val result = withContext(Dispatchers.IO) { runCatching { runner.start(target, settings, activeRules) } }
            result.onSuccess {
                connectedProfileId = target.id
                status = "Connected to ${target.displayName} (${target.protocolName})"
            }.onFailure {
                connectedProfileId = null
                status = "Connection failed: ${SubscriptionImporter.describe(it)}"
                appendLog("connect failed: ${SubscriptionImporter.describe(it)}")
            }
            busy = false
        }
    }

    fun disconnect() {
        scope.launch {
            withContext(Dispatchers.IO) { runner.stop() }
            connectedProfileId = null
            status = "Disconnected"
        }
    }

    fun testConnection() {
        scope.launch {
            busy = true
            status = "Testing the connection ..."
            val result = withContext(Dispatchers.IO) { runCatching { runner.testConnection() } }
            result.onSuccess { status = it }
                .onFailure { status = "Test failed: ${SubscriptionImporter.describe(it)}" }
            busy = false
        }
    }

    // ----------------------------------------------------------- subscriptions

    fun addSubscription(name: String, url: String, sendHwid: Boolean) {
        if (url.isBlank()) {
            status = "Subscription URL is empty"
            return
        }
        val subscription = Subscription(
            name = name.trim().ifBlank { "Subscription ${subscriptions.size + 1}" },
            url = url.trim(),
            sendHwid = sendHwid,
        )
        subscriptions.add(subscription)
        persist()
        refreshSubscription(subscription)
    }

    fun refreshSubscription(subscription: Subscription) {
        scope.launch {
            busy = true
            status = "Updating ${subscription.displayName} ..."
            val proxyPort = if (settings.fetchSubscriptionsThroughProxy && runner.running) {
                runner.socksPort.takeIf { it > 0 }
            } else {
                null
            }
            val headers = settings.hwidHeaders(subscription.sendHwid)
            appendLog(
                "updating ${subscription.displayName} from ${subscription.url}" +
                    if (proxyPort != null) " through the connected profile (127.0.0.1:$proxyPort)" else ""
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val body = SubscriptionImporter.download(subscription.url, proxyPort, headers)
                    SubscriptionImporter.parse(body)
                }
            }
            result.onSuccess { beans ->
                profiles.removeAll { it.subscriptionId == subscription.id }
                beans.forEach { bean ->
                    profiles.add(Profile(name = bean.name, bean = bean, subscriptionId = subscription.id))
                }
                subscription.lastUpdated = System.currentTimeMillis()
                subscription.lastError = null
                subscription.profileCount = beans.size
                if (selectedProfileId == null) selectedProfileId = profiles.firstOrNull()?.id
                status =
                    if (beans.isEmpty()) "No profiles found in ${subscription.displayName}"
                    else "Updated ${subscription.displayName}: ${beans.size} profile(s)"
                appendLog(status)
            }.onFailure {
                subscription.lastError = SubscriptionImporter.describe(it)
                status = "Subscription update failed: ${subscription.lastError}"
                appendLog("subscription update failed: ${subscription.lastError}")
            }
            persist()
            busy = false
        }
    }

    fun refreshAllSubscriptions() {
        subscriptions.toList().forEach { refreshSubscription(it) }
    }

    fun removeSubscription(subscription: Subscription) {
        profiles.removeAll { it.subscriptionId == subscription.id }
        subscriptions.remove(subscription)
        persist()
        status = "Removed ${subscription.displayName}"
    }

    // ------------------------------------------------------------------- rules

    fun addRule(rule: RoutingRule) {
        if (!rule.hasMatchers) {
            status = "A rule needs at least one matcher (domains, ip, port, network or protocol)"
            return
        }
        rules.add(rule)
        persist()
        status = "Rule added, it applies the next time you connect"
    }

    fun removeRule(rule: RoutingRule) {
        rules.remove(rule)
        persist()
        status = "Rule removed"
    }

    fun toggleRule(rule: RoutingRule) {
        rule.enabled = !rule.enabled
        persist()
    }

    // --------------------------------------------------------------- log & life

    fun clearLogs() {
        logs.clear()
    }

    fun logText(): String = synchronized(logs) { logs.joinToString("\n") }

    fun shutdown() {
        runner.stop()
        persist()
    }

    private fun persist() {
        store.profiles.clear()
        store.profiles.addAll(profiles)
        store.subscriptions.clear()
        store.subscriptions.addAll(subscriptions)
        store.rules.clear()
        store.rules.addAll(rules)
        store.settings = settings
        store.settings.selectedProfileId = selectedProfileId
        store.save()
    }

    fun appendLog(line: String) {
        synchronized(logs) {
            logs.add(line)
            while (logs.size > 800) logs.removeAt(0)
        }
    }

    /** Lets a verification run open a specific tab: `-Dowenclave.section=RULES`. */
    private fun initialSection(): Section {
        val requested = System.getProperty("owenclave.section")?.trim().orEmpty()
        if (requested.isEmpty()) return Section.PROFILES
        return Section.entries.firstOrNull { it.name.equals(requested, ignoreCase = true) } ?: Section.PROFILES
    }

}
