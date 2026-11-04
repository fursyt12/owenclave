package androidx.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.DesktopLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.nekohasekai.sagernet.SagerNet

open class ComponentActivity : Activity(), LifecycleOwner {
    private val desktopLifecycle = DesktopLifecycle(this)
    override val lifecycle: Lifecycle
        get() = desktopLifecycle

    var composeContent by mutableStateOf<(@Composable () -> Unit)?>(null)

    init {
        attachBaseContext(SagerNet.application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        desktopLifecycle.dispatch(Lifecycle.Event.ON_CREATE)
    }

    override fun onStart() {
        super.onStart()
        desktopLifecycle.dispatch(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        desktopLifecycle.dispatch(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        desktopLifecycle.dispatch(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        desktopLifecycle.dispatch(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        desktopLifecycle.dispatch(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun finish() {
        DesktopActivityStack.pop(this)
        super.finish()
    }
}

fun ComponentActivity.enableEdgeToEdge() {}

object DesktopActivityStack {
    val stack = mutableStateListOf<ComponentActivity>()

    init {
        Context.activityStarter = { _, intent ->
            startActivityFromIntent(intent)
        }
    }

    fun startActivityFromIntent(intent: Intent) {
        val className = intent.component?.className ?: return
        runCatching {
            val clazz = Class.forName(className)
            if (ComponentActivity::class.java.isAssignableFrom(clazz)) {
                val existing = stack.find { it.javaClass.name == className }
                if (existing != null && className.endsWith("ComposeMainActivity")) {
                    while (stack.size > 1 && stack.last() !== existing) {
                        val top = stack.removeLast()
                        top.onStop()
                        top.onDestroy()
                    }
                    existing.onNewIntent(intent)
                    existing.onResume()
                    return
                }
                val activity = clazz.getDeclaredConstructor().newInstance() as ComponentActivity
                activity.intent = intent
                push(activity)
            }
        }
    }

    fun push(activity: ComponentActivity) {
        stack.lastOrNull()?.onPause()
        stack.add(activity)
        activity.onCreate(null)
        activity.onStart()
        activity.onResume()
    }

    fun pop(activity: ComponentActivity) {
        if (stack.size <= 1) return
        if (stack.remove(activity)) {
            activity.onPause()
            activity.onStop()
            activity.onDestroy()
            stack.lastOrNull()?.onResume()
        }
    }

    @Composable
    fun Host(root: ComponentActivity) {
        if (stack.isEmpty()) {
            push(root)
        }
        val active = stack.lastOrNull() ?: root
        CompositionLocalProvider(
            LocalContext provides active,
            LocalLifecycleOwner provides active,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                active.composeContent?.invoke()
            }
        }
    }
}

