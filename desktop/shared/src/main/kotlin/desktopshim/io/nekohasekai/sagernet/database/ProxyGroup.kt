package io.nekohasekai.sagernet.database

/**
 * Desktop replacement for the Room backed Android `ProxyGroup`.
 *
 * The shared `ConfigBuilder.kt` only reads the front/landing proxy of the group
 * a profile belongs to, so the shim keeps those two fields plus the id and name.
 * Everything else (subscription beans, ordering, icon) is not compiled into
 * `:desktop:shared`.
 */
class ProxyGroup(
    var id: Long = 0L,
    var name: String? = null,
    var frontProxy: Long = -1L,
    var landingProxy: Long = -1L,
) {

    /** Mirrors `ProxyGroup.displayName`; Android falls back to `R.string.group_default`. */
    fun displayName(): String {
        return name.takeIf { !it.isNullOrEmpty() } ?: "Ungrouped"
    }

    /**
     * Desktop replacement for `ProxyGroup.Dao`. Empty by default; the desktop
     * client can install its own subclass on `SagerDatabase.groupDao`.
     */
    open class Dao {

        open fun getById(groupId: Long): ProxyGroup? = null

    }

}
