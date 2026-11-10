package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.net.Network
import android.os.PowerManager
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

open class ProxyService : Service(),
    BaseService.Interface,
    LocalResolver {
    override val data = BaseService.Data(this)
    override val tag: String get() = "SagerNetProxyService"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-proxy", true)
    override var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    override var underlyingNetwork: Network? = null
    private var networkListenerIsRunning = false

    override suspend fun preInit() {
        if (DataStore.serviceMode == Key.MODE_VPN || DataStore.appendHttpProxy) {
            if (!DataStore.requireHttp) {
                DataStore.requireHttp = true
            }
        }
        networkListenerIsRunning = true
        DefaultNetworkListener.start(this) {
            if (networkListenerIsRunning) {
                SagerNet.reloadNetwork(it)
                underlyingNetwork = it
            }
        }
    }

    override suspend fun startProcesses() {
        val proxy = data.proxy!!
        proxy.v2rayPoint.withLocalResolver(this)
        super.startProcesses()
        if (proxy.pluginConfigs.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (port in proxy.pluginConfigs.keys) {
                    awaitLocalPort(port, 10_000L)
                }
            }
        }
        runCatching { onStarted?.invoke() }.onFailure { Logs.w(it) }
    }

    override fun killProcesses() {
        runCatching { onStopped?.invoke() }.onFailure { Logs.w(it) }
        data.proxy?.v2rayPoint?.withLocalResolver(null)
        super.killProcesses()
        networkListenerIsRunning = false
        GlobalScope.launch(Dispatchers.Default) { DefaultNetworkListener.stop(this) }
    }

    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:proxy")
            .apply { acquire() }
    }

    override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)

    override fun onDestroy() {
        super<Service>.onDestroy()
        data.binder.close()
    }

    private suspend fun awaitLocalPort(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                }
                true
            }.getOrDefault(false)
            if (connected) return
            delay(50L)
        }
    }

    companion object {
        @Volatile
        var onStarted: (() -> Unit)? = null

        @Volatile
        var onStopped: (() -> Unit)? = null
    }
}
