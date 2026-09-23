package android.content.pm

/**
 * Desktop compile-only stand-in for `android.content.pm.PackageManager`.
 *
 * Only the one constant used by the shared `ConfigBuilder.kt` is provided. The
 * desktop client has no package manager, so `checkSelfPermission` in
 * `desktopshim/.../ktx/App.kt` always answers `PERMISSION_GRANTED`.
 */
object PackageManager {

    const val PERMISSION_GRANTED = 0
    const val PERMISSION_DENIED = -1

}
