package android.os

/**
 * Desktop compile-only stand-in for `android.os.Build`.
 *
 * `Build.VERSION.SDK_INT` is reported as Android 14 so the shared
 * `ConfigBuilder.kt` takes its modern (API >= 28/29) permission code path. The
 * two Android-only branches it guards are handled by `desktopshim` as follows:
 *
 *  - `checkSelfPermission` always returns `PERMISSION_GRANTED` (there is no
 *    runtime permission model on desktop);
 *  - `SagerNet.location.isLocationEnabled` is always `false`, so a routing rule
 *    that matches on SSID fails with `ROUTE_ALERT_LOCATION_DISABLED` instead of
 *    silently producing a rule the core cannot evaluate.
 */
object Build {

    object VERSION {

        const val SDK_INT = 34

    }

    object VERSION_CODES {

        const val N = 24
        const val N_MR1 = 25
        const val O = 26
        const val O_MR1 = 27
        const val P = 28
        const val Q = 29

    }

}
