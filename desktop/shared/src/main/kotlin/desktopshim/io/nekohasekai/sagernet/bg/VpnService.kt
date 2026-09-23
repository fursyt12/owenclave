package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.DataStore

/**
 * Desktop replacement for the fake DNS constants of the Android
 * `bg.VpnService`.
 *
 * The Android service derives these from the experimental flags and the same
 * values are needed by the shared `ConfigBuilder.kt` when it emits the fake IP
 * pools of a `fakedns` server. The defaults below are copied verbatim from
 * `app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt`, and the flag
 * lookups keep the desktop output identical to a stock Android install (on
 * desktop the experimental flag store is empty unless the client sets a value).
 *
 * This is intentionally an `object`, not the Android `android.net.VpnService`
 * subclass: the desktop core runs as a child process and never brings up a TUN
 * device from shared code.
 */
object VpnService {

    val FAKEDNS_VLAN4_CLIENT =
        DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv4Pool")?.substringBefore("/") ?: "198.18.0.0"

    val FAKEDNS_VLAN4_CLIENT_PREFIX =
        DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv4Pool")?.substringAfter("/")?.toInt() ?: 15

    val FAKEDNS_VLAN4_CLIENT_POOL_SIZE =
        DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv4PoolSize")?.toInt() ?: 65535

    val FAKEDNS_VLAN6_CLIENT =
        DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv6Pool")?.substringBefore("/") ?: "fc00::"

    val FAKEDNS_VLAN6_CLIENT_PREFIX =
        DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv6Pool")?.substringAfter("/")?.toInt() ?: 18

    val FAKEDNS_VLAN6_CLIENT_POOL_SIZE =
        DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv6PoolSize")?.toInt() ?: 65535

}
