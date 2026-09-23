package io.nekohasekai.sagernet.database

/**
 * Desktop replacement for the Room database `SagerDatabase`.
 *
 * It is kept as an abstract class with a `companion object`, exactly like the
 * Android original, because the shared Java beans (`ChainBean`,
 * `BalancerBean`) call `SagerDatabase.Companion.getProxyDao()` directly.
 *
 * There is no database on desktop: profiles are owned by the desktop client and
 * routing rules live in its own model. The shared `ConfigBuilder.kt` only
 * reaches the database through the three DAOs below, so each one is an
 * overridable, empty-by-default implementation. With the defaults a lone
 * profile builds exactly like on Android; groups, chains and balancers simply
 * resolve to "not found".
 *
 * Wiring from the desktop client (exact signatures, install before building a
 * config):
 *
 * ```
 * // Profiles, chains, balancers:
 * SagerDatabase.proxyDao = object : ProxyEntity.Dao() {
 *     override fun getEntities(proxyIds: List<Long>): List<ProxyEntity> = ...
 *     override fun getById(proxyId: Long): ProxyEntity? = ...
 *     override fun getByGroup(groupId: Long): List<ProxyEntity> = ...
 * }
 *
 * // Groups (front/landing proxy):
 * SagerDatabase.groupDao = object : ProxyGroup.Dao() {
 *     override fun getById(groupId: Long): ProxyGroup? = ...
 * }
 *
 * // Routing rules. This is the hook that replaces the Android Room query
 * // `SELECT * FROM rules WHERE enabled = :enabled ORDER BY userOrder`:
 * SagerDatabase.rulesDao = object : RuleEntity.Dao() {
 *     override fun enabledRules(enabled: Boolean): List<RuleEntity> = ...
 * }
 * ```
 */
abstract class SagerDatabase {

    companion object {

        /**
         * Overridable profile source; empty means "no chains/groups/balancers".
         * Deliberately a property with a getter (not `@JvmField`) so the shared
         * Java beans can keep calling `SagerDatabase.Companion.getProxyDao()`.
         */
        @Volatile
        var proxyDao: ProxyEntity.Dao = ProxyEntity.Dao()

        /** Overridable group source; empty means no front/landing proxy. */
        @Volatile
        var groupDao: ProxyGroup.Dao = ProxyGroup.Dao()

        /** Overridable rule source; empty means no extra routing rules. */
        @Volatile
        var rulesDao: RuleEntity.Dao = RuleEntity.Dao()

    }

}
