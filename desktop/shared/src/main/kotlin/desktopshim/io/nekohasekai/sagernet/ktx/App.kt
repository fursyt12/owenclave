package io.nekohasekai.sagernet.ktx

import android.content.ContentResolver
import android.content.pm.PackageManager

/**
 * Desktop replacement for the Android `ktx.app` extension
 * (`val app get() = SagerNet.application`).
 *
 * Only the members the shared `ConfigBuilder.kt` touches are provided. It is
 * used exclusively by the Android-only SSID/location routing branch, where:
 *
 *  - `checkSelfPermission` reports every permission as granted, because the
 *    desktop client has no runtime permission model;
 *  - `contentResolver` is a dummy that is only passed to
 *    `Settings.Secure.getInt`, which is unreachable because
 *    `Build.VERSION.SDK_INT >= Build.VERSION_CODES.P`.
 */
val app: DesktopApplication get() = DesktopApplication

object DesktopApplication {

    val contentResolver: ContentResolver = ContentResolver()

    fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED

}
