package io.nekohasekai.sagernet.database

/**
 * Desktop replacement for the Room/Parcelable backed Android `RuleEntity`.
 *
 * The field names, types and defaults are copied from the Android source so
 * `ConfigBuilder.kt` turns the desktop client's rules into byte-for-byte the
 * same V2Ray routing rules. [isBypassRule], [isProxyRule] and [displayName] are
 * copied verbatim; the Android-only `mkSummary`/`displayOutbound` helpers (which
 * need `Context` and `R`) are not modelled because no shared code uses them.
 *
 * The desktop client builds these and installs them through
 * `SagerDatabase.rulesDao` (see [Dao] and `SagerDatabase`).
 */
class RuleEntity(
    var id: Long = 0L,
    var name: String = "",
    var userOrder: Long = 0L,
    var enabled: Boolean = false,
    var domains: String = "",
    var ip: String = "",
    var port: String = "",
    var sourcePort: String = "",
    var network: String = "",
    var source: String = "",
    var protocol: String = "",
    var attrs: String = "",
    var outbound: Long = 0,
    var packages: List<String> = listOf(),
    var ssid: String = "",
    var networkType: Set<String> = emptySet(),
    var customPackageNames: List<String> = listOf(),
) {

    /** Mirrors `RuleEntity.isBypassRule` from the Android source. */
    fun isBypassRule(): Boolean {
        return (domains.isNotEmpty() && ip.isEmpty() || ip.isNotEmpty() && domains.isEmpty()) && port.isEmpty() && sourcePort.isEmpty() && network.isEmpty() && source.isEmpty() && protocol.isEmpty() && attrs.isEmpty() && outbound == -1L && packages.isEmpty() && customPackageNames.isEmpty() && ssid.isEmpty() && networkType.isEmpty()
    }

    /** Mirrors `RuleEntity.isProxyRule` from the Android source. */
    fun isProxyRule(): Boolean {
        return !(domains.isNotEmpty() && ip.isNotEmpty()) && outbound == 0L
    }

    /** Mirrors `RuleEntity.displayName` from the Android source. */
    fun displayName(): String {
        return name.takeIf { it.isNotEmpty() } ?: "Rule $id"
    }

    /**
     * Desktop replacement for `RuleEntity.Dao`.
     *
     * [enabledRules] has an overridable empty default, which is the hook the
     * desktop client uses to feed its own rules into the shared builder. Wire it
     * up without touching this module:
     *
     * ```
     * SagerDatabase.rulesDao = object : RuleEntity.Dao() {
     *     override fun enabledRules(enabled: Boolean): List<RuleEntity> =
     *         desktopRules.filter { it.enabled == enabled }.map { it.toRuleEntity() }
     * }
     * ```
     *
     * The Android DAO parameter has a `true` default, so `enabledRules()` keeps
     * the same call shape as `ConfigBuilder.kt` uses.
     */
    open class Dao {

        open fun enabledRules(enabled: Boolean = true): List<RuleEntity> = emptyList()

    }

}
