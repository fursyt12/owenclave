package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.DesktopLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.nekohasekai.sagernet.SagerNet

val LocalContext = staticCompositionLocalOf<Context> { SagerNet.application }

private val defaultLifecycleOwner = object : LifecycleOwner {
    override val lifecycle: Lifecycle = DesktopLifecycle(this)
}

val LocalLifecycleOwner = staticCompositionLocalOf<LifecycleOwner> { defaultLifecycleOwner }

