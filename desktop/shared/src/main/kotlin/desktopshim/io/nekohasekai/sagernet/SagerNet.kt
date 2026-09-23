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

    /** Location of the last known network location, an Android only feature. */
    @JvmField
    @Volatile
    var location: String? = null

    fun reloadService() {
        // no Android service to reload on desktop
    }

    fun file(name: String): File = File(deviceStorage.filesDir, name).apply {
        parentFile?.mkdirs()
    }

}
