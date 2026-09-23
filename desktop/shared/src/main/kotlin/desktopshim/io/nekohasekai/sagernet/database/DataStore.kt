package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.TunImplementation

/**
 * Desktop replacement for the Android preference backed {@code DataStore}.
 *
 * The shared protocol code only reads a handful of settings. On desktop the
 * client owns these values directly instead of a SharedPreferences store, and
 * the subset below is everything the Android app and the desktop client have in
 * common. Android-only settings (TUN, per-app proxy, fake DNS, ...) live in the
 * Android implementation of the same object.
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

    /**
     * Android only feature flags. The shared importers query them to decide
     * whether a protocol extension (for example Snell v6 user keys) is compiled
     * in. On desktop everything is available, so the store answers `false`
     * unless the client opts in explicitly.
     */
    val experimentalFlagsProperties = ExperimentalFlagsProperties()

}

/**
 * Minimal key/value store for feature flags, mirroring the API of the Android
 * `PreferenceDataStore` wrapper used by the app.
 */
class ExperimentalFlagsProperties {

    private val values = HashMap<String, Boolean>()

    fun getBooleanProperty(key: String): Boolean = values[key] ?: false

    fun putBooleanProperty(key: String, value: Boolean) {
        values[key] = value
    }

    fun remove(key: String) {
        values.remove(key)
    }

}
