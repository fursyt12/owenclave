package io.nekohasekai.sagernet

import java.io.File

/**
 * Desktop replacement for the Android `SagerNet` application object.
 *
 * Only the small surface that the shared protocol code touches is provided.
 * The Android version exposes the application `Context` as `deviceStorage`, so
 * the desktop shim mimics the few `Context` members that shared code uses.
 */
object SagerNet {

    /** Mimics the subset of `android.content.Context` used by shared code. */
    class DeviceStorage(private val dir: File) {

        init {
            dir.mkdirs()
            noBackupFilesDir.mkdirs()
        }

        val noBackupFilesDir: File get() = File(dir, "no_backup")

        val filesDir: File get() = dir

        val cacheDir: File get() = File(dir, "cache")

        val packageName: String get() = BuildConfig.APPLICATION_ID

        fun getDir(name: String, mode: Int): File = File(dir, "app_$name").apply { mkdirs() }

        override fun toString(): String = dir.absolutePath

    }

    @JvmField
    val deviceStorage = DeviceStorage(DesktopEnv.dataDir)

    /** Set while the desktop client has a running core instance. */
    @JvmField
    @Volatile
    var started: Boolean = false

    /**
     * Mimics the subset of `android.location.LocationManager` used by shared
     * code. Desktop has no network location provider, so the service always
     * reports itself as disabled; a routing rule that matches on SSID therefore
     * fails with `ROUTE_ALERT_LOCATION_DISABLED` instead of silently emitting a
     * rule the core cannot evaluate.
     */
    class LocationService {

        val isLocationEnabled: Boolean = false

    }

    /** Android only feature; see [LocationService]. */
    @JvmField
    val location = LocationService()

    fun reloadService() {
        // no Android service to reload on desktop
    }

    fun file(name: String): File = File(deviceStorage.filesDir, name).apply {
        parentFile?.mkdirs()
    }

}
