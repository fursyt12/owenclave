package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.RouteMode
import io.nekohasekai.sagernet.TLS_FRAGMENTATION_METHOD
import io.nekohasekai.sagernet.TunImplementation
import java.util.Properties

/**
 * Desktop replacement for the Android preference backed {@code DataStore}.
 *
 * The Android version is a `PreferenceDataStore` backed by SharedPreferences.
 * Desktop has no preference framework, so the values are plain properties that
 * the desktop client owns directly. Every member below is one that the shared
 * `ConfigBuilder.kt` (or the rest of the shared protocol code) actually reads;
 * the defaults are copied from the Android implementation so a profile produces
 * the same config as on a stock Android install.
 *
 * Android-only settings (TUN addressing, per-app proxy, fake DNS pools, ...) are
 * not modelled here; the shared `bg.VpnService` shim only exposes the fake DNS
 * constants ConfigBuilder needs.
 */
object DataStore {

    /** Verbosity of the core and plugin logs, see [LogLevel]. */
    var logLevel: Int = LogLevel.WARNING

    /** Android service mode, on desktop always [Key.MODE_VPN]. */
    var serviceMode: String = Key.MODE_VPN

    /** TUN implementation, on desktop always [TunImplementation.SYSTEM]. */
    var tunImplementation: Int = TunImplementation.SYSTEM

    /** Id of the profile that is currently connected, 0 when disconnected. */
    var startedProfile: Long = 0L

    // region routing

    /** [RouteMode.RULE], [RouteMode.GLOBAL] or [RouteMode.DIRECT]. */
    var routeMode: Int = RouteMode.RULE
    var domainStrategy: String = "AsIs"
    var outboundDomainStrategy: String = "AsIs"
    var outboundDomainStrategyForDirect: String = "AsIs"
    var outboundDomainStrategyForServer: String = "AsIs"
    var allowAccess: Boolean = false
    var trafficSniffing: Boolean = true
    var destinationOverride: Boolean = false

    // endregion

    // region DNS

    var remoteDns: String = "tcp://1.1.1.1"

    /**
     * Android picks a locale dependent default (223.5.5.5 for CN, ...). The
     * desktop client picks the neutral default; the client can overwrite it.
     */
    var directDns: String = "tcp://1.1.1.1"
    var bootstrapDns: String = ""
    var useLocalDnsAsDirectDns: Boolean = false
    var useLocalDnsAsBootstrapDns: Boolean = true
    var enableFakeDns: Boolean = false
    var hijackDns: Boolean = false
    var hosts: String = ""
    var enableDnsRouting: Boolean = true
    var remoteDnsQueryStrategy: String = "UseIP"
    var directDnsQueryStrategy: String = "UseIP"
    var ednsClientIp: String = ""

    // endregion

    // region fragmentation / protocol flags

    var enableFragment: Boolean = false
    var enableFragmentForDirect: Boolean = false
    var fragmentMethod: Int = TLS_FRAGMENTATION_METHOD.TLS_RECORD_FRAGMENTATION
    var realityDisableX25519Mlkem768: Boolean = false
    var hysteria2OmitMaxDatagramFrameSize: Boolean = false
    var grpcServiceNameCompat: Boolean = false

    // endregion

    // region outbound socks proxy chain

    var socksProxyChainEnabled: Boolean = false
    var socksProxyChainHost: String = ""
    var socksProxyChainPort: Int = 0
    var socksProxyChainUsername: String = ""
    var socksProxyChainPassword: String = ""

    var enableUnlockRu: Boolean = false
    var directProxyMode: Boolean = false

    // endregion

    // region inbounds

    var socksPort: Int = 2080
    var localDNSPort: Int = 6450
    var httpPort: Int = 9080
    var transproxyPort: Int = 9200
    var requireSocks: Boolean = true
    var socksUsername: String = ""
    var socksPassword: String = ""
    var socksUDP: Boolean = true
    var requireHttp: Boolean = false
    var httpUsername: String = ""
    var httpPassword: String = ""
    var requireTransproxy: Boolean = false
    var requireDnsInbound: Boolean = false
    var connectionTestURL: String = CONNECTION_TEST_URL
    var profileTrafficStatistics: Boolean = true
    var interruptReusedConnections: Boolean = true

    // endregion

    /**
     * Android only feature flags. The shared importers query them to decide
     * whether a protocol extension (for example Snell v6 user keys) is compiled
     * in. On desktop everything is available, so the store answers `false`
     * unless the client opts in explicitly.
     */
    val experimentalFlagsProperties = ExperimentalFlagsProperties()

}

/**
 * Replacement for the `java.util.Properties` store the Android `DataStore`
 * keeps behind `experimentalFlagsProperties`. It is a real [Properties]
 * instance, so the desktop client can load or override flags exactly like the
 * Android app does (its defaults are simply empty).
 */
class ExperimentalFlagsProperties : Properties() {

    fun getBooleanProperty(key: String): Boolean = getProperty(key) == "true"

    fun putBooleanProperty(key: String, value: Boolean) {
        setProperty(key, value.toString())
    }

    fun putStringProperty(key: String, value: String?) {
        if (value == null) remove(key) else setProperty(key, value)
    }

}
