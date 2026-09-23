package io.nekohasekai.sagernet.utils

/**
 * Desktop replacement for the Android `PackageCache`.
 *
 * Android keeps a package name -> UID map so that per-app routing rules can be
 * turned into `uid` matchers. Desktop has no Android package manager, so the
 * map is empty by default and a rule that selects packages reports
 * `ROUTE_ALERT_ALL_PACKAGES_UNINSTALLED` (the same alert Android raises when
 * none of the selected packages are installed) instead of emitting a rule that
 * the core cannot evaluate.
 *
 * The desktop client can install its own mapping with [packageUids] (for
 * example from a manually maintained list) without touching this module.
 */
object PackageCache {

    /** Package name -> Linux UID, empty until the desktop client provides one. */
    @Volatile
    var packageUids: Map<String, Int> = emptyMap()

    /** No asynchronous loading happens on desktop; kept for source compatibility. */
    fun awaitLoadSync() {
        // no-op
    }

    /** Returns the UID of [packageName], or `null` when it is not known. */
    operator fun get(packageName: String): Int? = packageUids[packageName]

}
