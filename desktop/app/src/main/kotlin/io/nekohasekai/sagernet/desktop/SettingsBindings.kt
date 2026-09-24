package io.nekohasekai.sagernet.desktop

/**
 * How one Android global preference key is honoured on the desktop client.
 *
 * This table is the *only* place the Android settings screen and the desktop
 * core config meet. It is maintained by hand on purpose: adding a preference on
 * Android makes [SettingsCatalogParser] pick it up, and the desktop settings
 * test fails the build until the key gets a binding here. That is the drift
 * guard: nothing is silently dropped and nothing is silently ignored.
 */
sealed interface Binding {

    /** Optional human readable note shown by `--print-settings`. */
    val note: String?

}

/**
 * Pushed into the shared `DataStore` shim, which is the object the Android
 * `ConfigBuilder.kt` reads. [member] is the shim property name; it is usually
 * the same as the preference key, the exceptions are documented in
 * [SettingsBindings].
 */
data class DataStoreMember(val member: String) : Binding {
    override val note: String? = null
}

/**
 * Handled by a desktop-only setting or a deliberate desktop override rather
 * than by a plain shim property (for example the LAN bypass rule, or the
 * route mode that desktop expresses in `postProcess`).
 */
data class DesktopOverride(override val note: String) : Binding

/**
 * Parsed and stored, but not consumed by the desktop core config (for example
 * the Android rule asset URLs, which only the Android updater downloads).
 * Kept enabled so the screen stays one to one.
 */
data class StoredOnly(override val note: String) : Binding

/**
 * No desktop equivalent. The row stays in its original section (structural
 * parity) but is rendered disabled with [reason].
 */
data class AndroidOnly(val reason: String) : Binding {
    override val note: String? = null
}

/** A key the desktop does not know about; only possible before the table is updated. */
data class Unsupported(val key: String) : Binding {
    override val note: String? = null
}

/**
 * The Android key -> desktop binding table.
 *
 * Grouped by the Android section so it can be reviewed against
 * `app/src/main/res/xml/global_preferences.xml`. Keys whose shim member differs
 * from the key itself are marked `NOTE`.
 */
object SettingsBindings {

    /** Android-only reason strings, kept short and specific. */
    private const val NO_VPN_SERVICE = "Android only: there is no VpnService on desktop"
    private const val NO_NOTIFICATION = "Android only: the desktop client has no notifications"
    private const val NO_PACKAGE_MANAGER = "Android only: Android package manager feature"

    private val TABLE: Map<String, Binding> = linkedMapOf(
        // ------------------------------------------------------- App settings
        "isAutoConnect" to AndroidOnly("Android only: the desktop client does not start on boot"),
        "appTheme" to DesktopOverride("the desktop window accent colour"),
        "nightTheme" to AndroidOnly("Android only: the desktop window always uses the dark theme"),
        "appLanguage" to AndroidOnly("Android only: the desktop UI ships in English"),
        "serviceMode" to AndroidOnly("$NO_VPN_SERVICE; use Transparent mode (TUN) in the desktop section"),
        "tunImplementation" to AndroidOnly("$NO_VPN_SERVICE; the desktop TUN device uses the system stack"),
        // NOTE: key `mtu` (VpnService MTU) drives the desktop TUN MTU field.
        "mtu" to DesktopOverride("the desktop TUN device MTU"),
        "meteredNetwork" to AndroidOnly(NO_VPN_SERVICE),
        "enablePcap" to AndroidOnly(NO_VPN_SERVICE),
        "discardICMP" to AndroidOnly(NO_VPN_SERVICE),
        "appTrafficStatistics" to AndroidOnly(NO_VPN_SERVICE),
        "profileTrafficStatistics" to DataStoreMember("profileTrafficStatistics"),
        "speedInterval" to AndroidOnly(NO_NOTIFICATION),
        "showDirectSpeed" to AndroidOnly(NO_NOTIFICATION),
        "logLevel" to DataStoreMember("logLevel"),
        "providerRootCA" to StoredOnly("Android core root CA provider; the desktop core keeps its system roots"),

        // ----------------------------------------------------- Route settings
        "enableVPNInterfaceIPv6Address" to AndroidOnly(NO_VPN_SERVICE),
        "proxyApps" to AndroidOnly("$NO_VPN_SERVICE; the desktop client cannot capture per-app traffic"),
        "allowAppsBypassVpn" to AndroidOnly(NO_VPN_SERVICE),
        // NOTE: ConfigBuilder does not read bypassLan; the desktop turns it into
        // the "Bypass private networks" routing rule (see DesktopConfigBuilder).
        "bypassLan" to DesktopOverride("the desktop \"Bypass private networks\" routing rule"),
        "domainStrategy" to DataStoreMember("domainStrategy"),
        "trafficSniffing" to DataStoreMember("trafficSniffing"),
        "destinationOverride" to DataStoreMember("destinationOverride"),
        // NOTE: key `hijackDns0` -> shim member `hijackDns`.
        "hijackDns0" to DataStoreMember("hijackDns"),
        "outboundDomainStrategy" to DataStoreMember("outboundDomainStrategy"),
        "outboundDomainStrategyForDirect" to DataStoreMember("outboundDomainStrategyForDirect"),
        "outboundDomainStrategyForServer" to DataStoreMember("outboundDomainStrategyForServer"),
        "rulesProvider" to StoredOnly("Android rule asset provider; the desktop client does not download rule assets"),
        "rulesGeositeUrl" to StoredOnly("Android geosite asset URL; stored for parity"),
        "rulesGeoipUrl" to StoredOnly("Android geoip asset URL; stored for parity"),
        // NOTE: the desktop expresses RouteMode.DIRECT by rewriting the upstream
        // outbound to freedom instead of emitting the Android bypass rule, and it
        // always evaluates the desktop rule list (see DesktopConfigBuilder).
        "routeMode" to DesktopOverride("the desktop route mode (desktop rules, proxy all or direct only)"),

        // -------------------------------------------------- Protocol settings
        "connectionTestURL" to DataStoreMember("connectionTestURL"),
        "socksProxyChainEnabled" to DataStoreMember("socksProxyChainEnabled"),
        "socksProxyChainHost" to DataStoreMember("socksProxyChainHost"),
        "socksProxyChainPort" to DataStoreMember("socksProxyChainPort"),
        "socksProxyChainUsername" to DataStoreMember("socksProxyChainUsername"),
        "socksProxyChainPassword" to DataStoreMember("socksProxyChainPassword"),
        "enableTwps2" to StoredOnly("Android plugin feature (zapret2); the desktop core ignores it"),
        "enableUnlockRu" to DataStoreMember("enableUnlockRu"),
        "directProxyMode" to DataStoreMember("directProxyMode"),
        "realityDisableX25519Mlkem768" to DataStoreMember("realityDisableX25519Mlkem768"),
        "hysteria2OmitMaxDatagramFrameSize" to DataStoreMember("hysteria2OmitMaxDatagramFrameSize"),
        "grpcServiceNameCompat" to DataStoreMember("grpcServiceNameCompat"),
        "enableFragment" to DataStoreMember("enableFragment"),
        "fragmentMethod" to DataStoreMember("fragmentMethod"),
        "enableFragmentForDirect" to DataStoreMember("enableFragmentForDirect"),
        // NOTE: key `interruptReusedConnections0` -> shim member `interruptReusedConnections`.
        "interruptReusedConnections0" to DataStoreMember("interruptReusedConnections"),
        "profileSecurityAdvisory" to AndroidOnly("Android only: the profile editor security advisory"),

        // ----------------------------------------------------------------- HWID
        // NOTE: sendHwid is used by the Android subscription updater only; the
        // desktop keeps it as its own typed setting (used for the same headers).
        "sendHwid" to DesktopOverride("the desktop HWID reporting default"),
        "resetHwid" to AndroidOnly("Android only: use \"Generate a new identity\" in the desktop section below"),

        // ----------------------------------------------------------- DNS settings
        "remoteDns" to DataStoreMember("remoteDns"),
        "remoteDnsQueryStrategy" to DataStoreMember("remoteDnsQueryStrategy"),
        "ednsClientIp" to DataStoreMember("ednsClientIp"),
        "useLocalDnsAsDirectDns" to DataStoreMember("useLocalDnsAsDirectDns"),
        "directDns" to DataStoreMember("directDns"),
        "directDnsQueryStrategy" to DataStoreMember("directDnsQueryStrategy"),
        "useLocalDnsAsBootstrapDns" to DataStoreMember("useLocalDnsAsBootstrapDns"),
        "bootstrapDns" to DataStoreMember("bootstrapDns"),
        // NOTE: key `dnsHosts0` -> shim member `hosts`.
        "dnsHosts0" to DataStoreMember("hosts"),
        "enableDnsRouting" to DataStoreMember("enableDnsRouting"),
        "enableFakeDns" to DataStoreMember("enableFakeDns"),

        // ------------------------------------------------------- Inbound settings
        "requireSocks" to DataStoreMember("requireSocks"),
        "socksPort" to DataStoreMember("socksPort"),
        "socksUsername" to DataStoreMember("socksUsername"),
        "socksPassword" to DataStoreMember("socksPassword"),
        "socksUDP" to DataStoreMember("socksUDP"),
        "requireHttp" to DataStoreMember("requireHttp"),
        "httpPort" to DataStoreMember("httpPort"),
        "httpUsername" to DataStoreMember("httpUsername"),
        "httpPassword" to DataStoreMember("httpPassword"),
        "appendHttpProxy" to AndroidOnly("Android only: the desktop client does not set a system HTTP proxy"),
        "httpProxyException" to AndroidOnly("Android only: the desktop client does not set a system HTTP proxy"),
        "requireTransproxy" to AndroidOnly("Android only: transparent proxy needs Android iptables integration"),
        "transproxyPort" to AndroidOnly("Android only: transparent proxy needs Android iptables integration"),
        "requireDnsInbound" to AndroidOnly("Android only: the local DNS UDS inbound is Android only"),
        "portLocalDns" to AndroidOnly("Android only: the local DNS UDS inbound is Android only"),
        "allowAccess" to DataStoreMember("allowAccess"),

        // ---------------------------------------------------------- Misc settings
        "showGroupName" to AndroidOnly(NO_NOTIFICATION),
        "alwaysShowAddress" to AndroidOnly("Android only: the Android profile list display"),
        "acquireWakeLock" to AndroidOnly("Android only: keeps the Android VPN service alive"),
        "stunServers" to StoredOnly("used by the Android STUN test; stored for parity"),
        "fabStyle" to AndroidOnly("Android only: the floating action button"),
        "useIECUnit" to AndroidOnly("Android only: statistics formatting in the Android UI"),
        "queryAllPackagesAlternativeMethod" to AndroidOnly(NO_PACKAGE_MANAGER),
        "pprofServer" to StoredOnly("Android debug instance pprof listener; stored for parity"),
        "experimentalFlags" to DataStoreMember("experimentalFlagsProperties"),
    )

    /** The binding for [key], or [Binding.Unsupported] when the table is stale. */
    fun bindingFor(key: String): Binding = TABLE[key] ?: Unsupported(key)

    /** Keys the table knows, for the drift test (a key missing from the XML is stale). */
    val keys: Set<String> get() = TABLE.keys

    /** The whole table, for documentation and the drift test. */
    val entries: Map<String, Binding> get() = TABLE

}
