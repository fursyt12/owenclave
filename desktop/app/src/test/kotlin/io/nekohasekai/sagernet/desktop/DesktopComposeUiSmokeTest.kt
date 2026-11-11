package io.nekohasekai.sagernet.desktop

import androidx.activity.DesktopActivityStack
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.use
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.compose.ComposeMainActivity
import io.nekohasekai.sagernet.ui.compose.NavDestination
import io.nekohasekai.sagernet.ui.compose.OwenclaveTheme
import io.nekohasekai.sagernet.ui.compose.PendingDeepLink
import io.nekohasekai.sagernet.ui.compose.screens.ProfileFieldState
import io.nekohasekai.sagernet.ui.compose.screens.UniversalProfileSettingsScreen
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopComposeUiSmokeTest {

    @Test
    fun `renders ComposeMainActivity across all navigation destinations and UniversalProfileSettingsScreen`() {
        SagerNet.application
        DesktopActivityStack.stack.clear()

        ImageComposeScene(width = 1180, height = 780).use { scene ->
            val mainActivity = ComposeMainActivity()
            scene.setContent {
                DesktopActivityStack.Host(mainActivity)
            }
            val initialImg = scene.render(0L)
            assertTrue(initialImg.width == 1180 && initialImg.height == 780)

            var frameNano = 16_000_000L
            for (dest in NavDestination.entries) {
                PendingDeepLink.pendingDestination.value = dest
                repeat(3) {
                    scene.render(frameNano)
                    frameNano += 16_000_000L
                }
            }

            // Exercise deep link routing to GroupScreen + quick-add dialog + Toast overlay
            val subIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("owenclave://add-subscription?url=https%3A%2F%2Fexample.com%2Fapi%2Fsub&hwid=1"),
            )
            mainActivity.onNewIntent(subIntent)
            android.widget.Toast.makeText(mainActivity, "Deep link received", android.widget.Toast.LENGTH_SHORT).show()
            repeat(4) {
                scene.render(frameNano)
                frameNano += 16_000_000L
            }
            PendingDeepLink.pendingSubscriptionAdd.value = null
            PendingDeepLink.pendingDestination.value = null
        }

        // Also verify compact phone width (3-tab pill mode) renders cleanly
        DesktopActivityStack.stack.clear()
        ImageComposeScene(width = 412, height = 892).use { scene ->
            val mainActivity = ComposeMainActivity()
            scene.setContent {
                DesktopActivityStack.Host(mainActivity)
            }
            val compactImg = scene.render(0L)
            assertTrue(compactImg.width == 412 && compactImg.height == 892)
        }

        val allTypes = listOf(
            ProxyEntity.TYPE_SOCKS to ProfileFieldState(),
            ProxyEntity.TYPE_HTTP to ProfileFieldState(),
            ProxyEntity.TYPE_HTTP to ProfileFieldState(security = "tls"),
            ProxyEntity.TYPE_SS to ProfileFieldState(),
            ProxyEntity.TYPE_SSR to ProfileFieldState(),
            ProxyEntity.TYPE_VMESS to ProfileFieldState(),
            ProxyEntity.TYPE_VLESS to ProfileFieldState(security = "reality"),
            ProxyEntity.TYPE_TROJAN to ProfileFieldState(security = "tls"),
            ProxyEntity.TYPE_NAIVE to ProfileFieldState(),
            ProxyEntity.TYPE_HYSTERIA2 to ProfileFieldState(),
            ProxyEntity.TYPE_SSH to ProfileFieldState(),
            ProxyEntity.TYPE_WG to ProfileFieldState(),
            ProxyEntity.TYPE_MIERU to ProfileFieldState(),
            ProxyEntity.TYPE_TUIC5 to ProfileFieldState(),
            ProxyEntity.TYPE_JUICITY to ProfileFieldState(),
            ProxyEntity.TYPE_HTTP3 to ProfileFieldState(),
            ProxyEntity.TYPE_ANYTLS to ProfileFieldState(),
            ProxyEntity.TYPE_SHADOWQUIC to ProfileFieldState(),
            ProxyEntity.TYPE_TRUSTTUNNEL to ProfileFieldState(),
            ProxyEntity.TYPE_SNELL to ProfileFieldState(),
            ProxyEntity.TYPE_MASQUE to ProfileFieldState(),
            ProxyEntity.TYPE_CHAIN to ProfileFieldState(),
            ProxyEntity.TYPE_CONFIG to ProfileFieldState(),
            ProxyEntity.TYPE_BALANCER to ProfileFieldState(),
        )

        for ((type, initial) in allTypes) {
            ImageComposeScene(width = 1000, height = 1600).use { scene ->
                scene.setContent {
                    OwenclaveTheme(themeId = 0, nightTheme = 1) {
                        UniversalProfileSettingsScreen(
                            profileType = type,
                            initialState = initial,
                            onBack = {},
                            onSave = {},
                        )
                    }
                }
                val img = scene.render(0L)
                assertTrue(img.width == 1000)
            }
        }

        if (!GraphicsEnvironment.isHeadless()) {
            var windowError: Throwable? = null
            SwingUtilities.invokeAndWait {
                var window: ComposeWindow? = null
                try {
                    DesktopActivityStack.stack.clear()
                    window = ComposeWindow()
                    window.setSize(1180, 780)
                    val mainActivity = ComposeMainActivity()
                    window.setContent {
                        DesktopActivityStack.Host(mainActivity)
                    }
                } catch (t: Throwable) {
                    windowError = t
                } finally {
                    runCatching { window?.dispose() }
                }
            }
            windowError?.let { throw it }
        }
    }
}
