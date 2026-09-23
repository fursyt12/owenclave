package android.content

/**
 * Desktop compile-only stand-in for `android.content.ContentResolver`.
 *
 * It only exists so `app.contentResolver` (see
 * `desktopshim/.../ktx/App.kt`) has the same shape as on Android. No desktop
 * code calls a method on it: the one use in the shared `ConfigBuilder.kt` sits
 * behind the Android-only SSID/location branch, which never reaches
 * `Settings.Secure.getInt` because `Build.VERSION.SDK_INT >= P`.
 */
class ContentResolver
