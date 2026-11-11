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
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
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

    /** Local DNS port bound by `v2rayPoint` (`TAG_DNS_IN`) for the current session, or 0. */
    @Volatile
    var activeDnsPort: Int = 0
        private set

    private data class VpnConfigOverrides(
        val requireSocks: Boolean,
        val socksUdp: Boolean,
        val trafficSniffing: Boolean,
        val hijackDns: Boolean,
        val requireDnsInbound: Boolean,
        val localDnsPort: Int,
    )

    private var savedVpnOverrides: VpnConfigOverrides? = null

    private fun restoreVpnOverrides() {
        val saved = savedVpnOverrides ?: return
        savedVpnOverrides = null
        DataStore.requireSocks = saved.requireSocks
        DataStore.socksUDP = saved.socksUdp
        DataStore.trafficSniffing = saved.trafficSniffing
        DataStore.hijackDns = saved.hijackDns
        DataStore.requireDnsInbound = saved.requireDnsInbound
        DataStore.localDNSPort = saved.localDnsPort
    }

    override suspend fun preInit() {
        restoreVpnOverrides()
        if (DataStore.serviceMode == Key.MODE_VPN) {
            val preferredDnsPort = DataStore.localDNSPort.takeIf { it in 1..65535 } ?: 6450
            val dnsPort = if (isLoopbackTcpAndUdpFree(preferredDnsPort)) {
                preferredDnsPort
            } else {
                allocateLoopbackDnsPort()
            }
            savedVpnOverrides = VpnConfigOverrides(
                requireSocks = DataStore.requireSocks,
                socksUdp = DataStore.socksUDP,
                trafficSniffing = DataStore.trafficSniffing,
                hijackDns = DataStore.hijackDns,
                requireDnsInbound = DataStore.requireDnsInbound,
                localDnsPort = DataStore.localDNSPort,
            )
            activeDnsPort = dnsPort
            DataStore.requireSocks = true
            DataStore.socksUDP = true
            DataStore.trafficSniffing = true
            DataStore.hijackDns = true
            DataStore.requireDnsInbound = true
            DataStore.localDNSPort = dnsPort
        } else {
            activeDnsPort = if (DataStore.requireDnsInbound) DataStore.localDNSPort else 0
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
        restoreVpnOverrides()
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
        withContext(Dispatchers.IO) {
            onStarted?.invoke(this@ProxyService)
        }
    }

    override fun killProcesses() {
        restoreVpnOverrides()
        data.proxy?.v2rayPoint?.withLocalResolver(null)
        super.killProcesses()
        networkListenerIsRunning = false
        GlobalScope.launch(Dispatchers.Default) { DefaultNetworkListener.stop(this) }
    }

    override fun stopSelf() {
        runCatching { onStopped?.invoke() }.onFailure { Logs.w(it) }
        super.stopSelf()
    }

    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:proxy")
            .apply { acquire() }
    }

    override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)

    override fun onDestroy() {
        runCatching { onStopped?.invoke() }.onFailure { Logs.w(it) }
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

    private fun isLoopbackTcpAndUdpFree(port: Int): Boolean {
        val lo = InetAddress.getByName("127.0.0.1")
        return runCatching {
            ServerSocket(port, 1, lo).use { }
            DatagramSocket(port, lo).use { }
            true
        }.getOrDefault(false)
    }

    private fun allocateLoopbackDnsPort(): Int {
        val lo = InetAddress.getByName("127.0.0.1")
        repeat(8) {
            val port = runCatching {
                ServerSocket(0, 1, lo).use { it.localPort }
            }.getOrDefault(0)
            if (port > 0 && isLoopbackTcpAndUdpFree(port)) return port
        }
        return 0
    }

    companion object {
        @Volatile
        var onStarted: ((ProxyService) -> Unit)? = null

        @Volatile
        var onStopped: (() -> Unit)? = null
    }
}
