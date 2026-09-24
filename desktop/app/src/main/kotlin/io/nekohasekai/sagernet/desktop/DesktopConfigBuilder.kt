package io.nekohasekai.sagernet.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.RouteMode
import io.nekohasekai.sagernet.TLS_FRAGMENTATION_METHOD
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.buildV2RayConfig

/** Raised when a profile cannot be expressed as a V2Ray config for the core. */
class UnsupportedProfileException(message: String) : Exception(message)

/**
 * An external engine that has to be started next to the core because the core
 * does not implement the protocol itself (NaiveProxy, olcrtc).
 *
 * [bean] is the very same object that `buildV2RayConfig` used, so the mapping
 * fields it mutates (`finalAddress`/`finalPort`) are already applied when the
 * plugin config is generated from it.
 */
data class ExternalPlugin(
    val bean: AbstractBean,
    val port: Int,
    val username: String,
    val password: String,
)

/** Result of [DesktopConfigBuilder.build]: the core JSON plus the plugins to run. */
class CoreConfig(val json: String, val plugins: List<ExternalPlugin>)

/**
 * Turns a desktop [Profile] into the JSON the core consumes.
 *
 * Unlike the earlier desktop-only builder this is a thin adapter over the real
 * Android generator (`fmt/ConfigBuilder.kt`, compiled into `:desktop:shared`),
 * so the desktop client understands every protocol the Android app does. The
 * shared generator is bound to the VPN service, Room and Android preferences, so
 * this adapter pushes the desktop settings into the `DataStore` shim, installs
 * the desktop routing rules on `SagerDatabase.rulesDao` and then strips the
 * Android-only inbounds from the result.
 */
object DesktopConfigBuilder {

    private val json = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    /**
     * The same private-network list the pre-generator desktop builder emitted.
     * Kept in one rule so DNS and local-network handling stay equivalent.
     */
    private val PRIVATE_NETWORKS = listOf(
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )

    /**
     * Every non-null [AbstractBean] is buildable: the shared generator has an
     * outbound branch for each protocol the Android app supports. NaiveProxy and
     * olcrtc are still supported, they just need an external plugin process (see
     * [CoreConfig.plugins]).
     */
    fun supports(bean: AbstractBean?): Boolean = bean != null

    fun unsupportedReason(bean: AbstractBean?): String = when (bean) {
        null -> "The profile is empty"
        else -> "${bean.javaClass.simpleName.removeSuffix("Bean")} is not supported by the desktop client yet"
    }

    /**
     * Builds the core configuration for [profile].
     *
     * @param bindInterface physical interface the outbounds have to be pinned to
     *   in transparent (TUN) mode, or `null` when the system proxy is used.
     */
    fun build(
        profile: Profile,
        settings: DesktopSettings,
        rules: List<RoutingRule> = emptyList(),
        bindInterface: String? = null,
    ): CoreConfig {
        val bean = profile.bean
        if (bean == null) {
            val raw = profile.customConfig
            if (raw.isNullOrBlank()) throw UnsupportedProfileException("The profile is empty")
            // Custom configs are never routed through the generator: the user
            // wrote them for the core already. Only the TUN interface binding is
            // injected, and only when there is one.
            return CoreConfig(postProcessCustom(raw, bindInterface), emptyList())
        }
        if (!supports(bean)) throw UnsupportedProfileException(unsupportedReason(bean))

        applySettings(settings)
        installRules(rules, settings.bypassPrivateNetworks)

        val entity = ProxyEntity(id = profile.id.hashCode().toLong(), groupId = 0L).putBean(bean)
        val result = buildV2RayConfig(entity)

        val direct = settings.routeMode == DesktopSettings.ROUTE_DIRECT
        val plugins = if (direct) {
            // The proxy outbound is replaced by a freedom outbound below, so no
            // external engine is needed at all.
            emptyList()
        } else {
            result.index.flatMap { index ->
                index.chain.map { (triple, proxy) ->
                    ExternalPlugin(proxy.requireBean(), triple.first, triple.second, triple.third)
                }
            }
        }

        return CoreConfig(
            postProcess(result.config, settings, bindInterface, result.outboundTagsCurrent.toSet()),
            plugins,
        )
    }

    // ------------------------------------------------------------------ settings

    /**
     * Pushes the desktop settings into the shared `DataStore` shim, in two
     * passes so the order is explicit:
     *
     * 1. Every Android global preference whose [Binding] is a [DataStoreMember] is
     *    applied from its persisted value (or its Android default). The member
     *    names are the shim property names; [applyToDataStore] is the single
     *    mapping point and throws for an unknown member.
     * 2. The desktop-only overrides run afterwards. They deliberately replace
     *    Android behaviour: there is no VpnService on desktop and the desktop
     *    expresses "direct only" in [postProcess] while always evaluating its own
     *    rule list.
     */
    private fun applySettings(settings: DesktopSettings) {
        // 1. the Android global preferences
        SettingsCatalogParser.load().entries.forEach { entry ->
            val binding = entry.binding
            if (binding is DataStoreMember) {
                applyToDataStore(binding.member, settings.storedOrDefault(entry.key))
            }
        }

        // 2. desktop-only overrides
        // MODE_PROXY (plus a non-SYSTEM TUN implementation) keeps ConfigBuilder
        // from emitting the Android VpnService plugin-protect arguments
        // (`--android_vpn` / `-V`) for SIP003 plugins.
        DataStore.serviceMode = Key.MODE_PROXY
        DataStore.tunImplementation = TunImplementation.GVISOR
        // Rules are always evaluated; ROUTE_DIRECT is expressed by turning the
        // proxy outbound into a freedom outbound in postProcess(). This keeps the
        // desktop rule list working for every route mode.
        DataStore.routeMode = RouteMode.RULE
        // The desktop client has no transparent proxy inbound and no Android
        // local-DNS socket; the TUN device is served by the SOCKS inbound.
        DataStore.requireTransproxy = false
        DataStore.requireDnsInbound = false
    }

    /**
     * Sets one member of the shared `DataStore` shim from its persisted string
     * form. [member] is the Android property name, which for most keys equals the
     * preference key; [SettingsBindings] documents the exceptions. Every
     * [DataStoreMember] in the binding table needs a branch here, which the
     * desktop settings test enforces.
     *
     * A null [value] resets the member to the Android `DataStore` default, so a
     * build never inherits a value from a previous build.
     */
    internal fun applyToDataStore(member: String, value: String?) {
        when (member) {
            // routing
            "domainStrategy" -> DataStore.domainStrategy = value ?: "AsIs"
            "trafficSniffing" -> DataStore.trafficSniffing = value.toBoolean()
            "destinationOverride" -> DataStore.destinationOverride = value.toBoolean()
            "hijackDns" -> DataStore.hijackDns = value.toBoolean()
            "outboundDomainStrategy" -> DataStore.outboundDomainStrategy = value ?: "AsIs"
            "outboundDomainStrategyForDirect" -> DataStore.outboundDomainStrategyForDirect = value ?: "AsIs"
            "outboundDomainStrategyForServer" -> DataStore.outboundDomainStrategyForServer = value ?: "AsIs"
            "profileTrafficStatistics" -> DataStore.profileTrafficStatistics = value.toBoolean()
            "allowAccess" -> DataStore.allowAccess = value.toBoolean()

            // DNS
            "remoteDns" -> DataStore.remoteDns = value ?: "tcp://1.1.1.1"
            "remoteDnsQueryStrategy" -> DataStore.remoteDnsQueryStrategy = value ?: "UseIP"
            "ednsClientIp" -> DataStore.ednsClientIp = value.orEmpty()
            "useLocalDnsAsDirectDns" -> DataStore.useLocalDnsAsDirectDns = value.toBoolean()
            "directDns" -> DataStore.directDns = value ?: "tcp://1.1.1.1"
            "directDnsQueryStrategy" -> DataStore.directDnsQueryStrategy = value ?: "UseIP"
            "useLocalDnsAsBootstrapDns" -> DataStore.useLocalDnsAsBootstrapDns = value.toBoolean()
            "bootstrapDns" -> DataStore.bootstrapDns = value.orEmpty()
            "hosts" -> DataStore.hosts = value.orEmpty()
            "enableDnsRouting" -> DataStore.enableDnsRouting = value.toBoolean()
            "enableFakeDns" -> DataStore.enableFakeDns = value.toBoolean()

            // logging / test URL
            "logLevel" -> DataStore.logLevel = value?.toIntOrNull() ?: LogLevel.INFO
            "connectionTestURL" ->
                DataStore.connectionTestURL = value ?: io.nekohasekai.sagernet.CONNECTION_TEST_URL

            // fragmentation and protocol flags
            "enableFragment" -> DataStore.enableFragment = value.toBoolean()
            "enableFragmentForDirect" -> DataStore.enableFragmentForDirect = value.toBoolean()
            "fragmentMethod" -> DataStore.fragmentMethod =
                value?.toIntOrNull() ?: TLS_FRAGMENTATION_METHOD.TLS_RECORD_FRAGMENTATION
            "interruptReusedConnections" -> DataStore.interruptReusedConnections = value.toBoolean()
            "realityDisableX25519Mlkem768" -> DataStore.realityDisableX25519Mlkem768 = value.toBoolean()
            "hysteria2OmitMaxDatagramFrameSize" -> DataStore.hysteria2OmitMaxDatagramFrameSize = value.toBoolean()
            "grpcServiceNameCompat" -> DataStore.grpcServiceNameCompat = value.toBoolean()
            "enableUnlockRu" -> DataStore.enableUnlockRu = value.toBoolean()
            "directProxyMode" -> DataStore.directProxyMode = value.toBoolean()

            // outbound SOCKS proxy chain
            "socksProxyChainEnabled" -> DataStore.socksProxyChainEnabled = value.toBoolean()
            "socksProxyChainHost" -> DataStore.socksProxyChainHost = value.orEmpty()
            "socksProxyChainPort" -> DataStore.socksProxyChainPort = value?.toIntOrNull() ?: 0
            "socksProxyChainUsername" -> DataStore.socksProxyChainUsername = value.orEmpty()
            "socksProxyChainPassword" -> DataStore.socksProxyChainPassword = value.orEmpty()

            // inbounds
            "requireSocks" -> DataStore.requireSocks = value.toBoolean()
            "socksPort" -> DataStore.socksPort = value?.toIntOrNull() ?: 2080
            "socksUsername" -> DataStore.socksUsername = value.orEmpty()
            "socksPassword" -> DataStore.socksPassword = value.orEmpty()
            "socksUDP" -> DataStore.socksUDP = value.toBoolean()
            "requireHttp" -> DataStore.requireHttp = value.toBoolean()
            "httpPort" -> DataStore.httpPort = value?.toIntOrNull() ?: 9080
            "httpUsername" -> DataStore.httpUsername = value.orEmpty()
            "httpPassword" -> DataStore.httpPassword = value.orEmpty()

            // experimental flags are a java.util.Properties document
            "experimentalFlagsProperties" -> {
                DataStore.experimentalFlagsProperties.clear()
                value?.takeIf { it.isNotBlank() }?.let { text ->
                    DataStore.experimentalFlagsProperties.load(java.io.StringReader(text))
                }
            }

            else -> throw IllegalStateException(
                "no DataStore mapping for shim member \"$member\": update DesktopConfigBuilder.applyToDataStore"
            )
        }
    }

    /** Android booleans are stored as text; anything but "true" is false. */
    private fun String?.toBoolean(): Boolean = this.equals("true", ignoreCase = true)

    // --------------------------------------------------------------------- rules

    /**
     * Installs the desktop routing rules on the shared `SagerDatabase` shim.
     *
     * The desktop model has no proxy ids, so the Android outbound encoding is
     * used directly: `0` is the selected profile, `-1` is the bypass (direct)
     * outbound and `-2` is the block outbound. `userOrder` keeps the list order,
     * and the private-network bypass is appended last exactly like the old
     * desktop builder did.
     */
    private fun installRules(rules: List<RoutingRule>, bypassPrivateNetworks: Boolean) {
        val entities = ArrayList<RuleEntity>()
        rules.forEachIndexed { index, rule ->
            entities += RuleEntity(
                id = index.toLong() + 1,
                name = rule.name,
                userOrder = index.toLong(),
                enabled = rule.enabled,
                domains = rule.domains,
                ip = rule.ip,
                port = rule.port,
                network = rule.network,
                protocol = rule.protocol,
                outbound = when (rule.target) {
                    RuleTarget.DIRECT.tag -> -1L
                    RuleTarget.BLOCK.tag -> -2L
                    else -> 0L
                },
            )
        }
        if (bypassPrivateNetworks) {
            entities += RuleEntity(
                id = entities.size.toLong() + 1,
                name = "Bypass private networks",
                userOrder = entities.size.toLong(),
                enabled = true,
                ip = PRIVATE_NETWORKS.joinToString("\n"),
                outbound = -1L,
            )
        }
        SagerDatabase.rulesDao = object : RuleEntity.Dao() {
            override fun enabledRules(enabled: Boolean): List<RuleEntity> =
                entities.filter { it.enabled == enabled }.sortedBy { it.userOrder }
        }
    }

    // -------------------------------------------------------------- post process

    /**
     * Removes everything that only makes sense inside the Android VPN service
     * and applies the desktop specific outbound tweaks.
     *
     * @param proxyTags tags of the outbounds that carry the selected profile;
     *   they are replaced by a direct outbound in [DesktopSettings.ROUTE_DIRECT].
     */
    private fun postProcess(
        config: String,
        settings: DesktopSettings,
        bindInterface: String?,
        proxyTags: Set<String>,
    ): String {
        val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrElse { return config }

        // 1. Drop the Android-only inbounds: the `ipc-in` UDS Android uses for
        //    per-app traffic stats, and the `ipc_dns.sock` UDS of the local DNS.
        val inbounds = root.getAsJsonArray("inbounds") ?: JsonArray()
        val keptInbounds = JsonArray()
        inbounds.forEach { element ->
            val inbound = element.asJsonObject
            val tag = inbound.get("tag")?.asString
            if (tag == TAG_IPC_IN || tag == TAG_DNS_IN) return@forEach
            keptInbounds.add(inbound)
        }
        root.add("inbounds", keptInbounds)
        val inboundTags = keptInbounds.mapNotNull { it.asJsonObject.get("tag")?.asString }.toSet()

        // 2. Routing rules that referenced a dropped inbound (`dns-in` fed the
        //    `dns-out` outbound) would make the core reject the config.
        root.getAsJsonObject("routing")?.let { routing ->
            val rules = routing.getAsJsonArray("rules")
            if (rules != null) {
                val keptRules = JsonArray()
                rules.forEach { element ->
                    val rule = element.asJsonObject
                    val inboundTag = rule.getAsJsonArray("inboundTag")
                    if (inboundTag != null && inboundTag.any { it.asString !in inboundTags }) return@forEach
                    keptRules.add(rule)
                }
                routing.add("rules", keptRules)
                if (keptRules.size() == 0) root.remove("routing")
            }
        }

        val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray()

        // 3. "Direct only" mode: the selected profile is not dialled, the core
        //    sends everything out directly. The tag is kept so the routing rules
        //    that point at it still resolve.
        if (settings.routeMode == DesktopSettings.ROUTE_DIRECT) {
            outbounds.forEach { element ->
                val outbound = element.asJsonObject
                if (outbound.get("tag")?.asString in proxyTags) {
                    outbound.addProperty("protocol", "freedom")
                    outbound.remove("settings")
                    outbound.remove("mux")
                    outbound.remove("smux")
                    outbound.remove("streamSettings")
                }
            }
        }

        // 4. Transparent mode: every outbound that dials a real socket (so
        //    everything except blackhole and the internal DNS) has to be pinned
        //    to the physical interface, otherwise it would loop back into TUN.
        if (!bindInterface.isNullOrBlank()) {
            outbounds.forEach { element ->
                element.asJsonObject.bindToInterface(bindInterface)
            }
        }

        return json.toJson(root)
    }

    /** Custom configs only get the TUN interface binding, nothing else. */
    private fun postProcessCustom(config: String, bindInterface: String?): String {
        if (bindInterface.isNullOrBlank()) return config
        val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrElse { return config }
        root.getAsJsonArray("outbounds")?.forEach { element ->
            element.asJsonObject.bindToInterface(bindInterface)
        }
        return json.toJson(root)
    }

    /** Pins every socket of this outbound to a network interface. */
    private fun JsonObject.bindToInterface(interfaceName: String) {
        val protocol = get("protocol")?.asString
        if (protocol == "blackhole" || protocol == "dns") return
        val streamSettings = getAsJsonObject("streamSettings") ?: JsonObject().also { add("streamSettings", it) }
        val sockopt = streamSettings.getAsJsonObject("sockopt") ?: JsonObject().also { streamSettings.add("sockopt", it) }
        sockopt.addProperty("bindToDevice", interfaceName)
    }

    private const val TAG_IPC_IN = "ipc-in"
    private const val TAG_DNS_IN = "dns-in"

}
