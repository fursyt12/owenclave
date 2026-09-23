package io.nekohasekai.sagernet.desktop

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Supervises the desktop proxy runtime: the Go core process and the external
 * engines (NaiveProxy, olcrtc) the core dials into through a local SOCKS
 * listener.
 *
 * This mirrors the Android architecture, where `bg/proto/V2RayInstance.kt`
 * starts the in-process core plus the plugin binaries and wires them together
 * through a local SOCKS listener.
 */
class CoreRunner(private val log: (String) -> Unit) {

    private var tunSession: TunSession? = null
    private var coreProcess: Process? = null
    private var coreConfigFile: File = File(DesktopRuntime.dataDir, "core.json")
    private val plugins = ExternalPlugins { line -> log(line) }

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    val socksPort: Int get() = settings?.socksPort ?: 0

    /** Whether any external plugin process is still running. */
    fun pluginAlive(): Boolean = plugins.alive

    private var settings: DesktopSettings? = null

    /** Starts [profile]; throws with a human readable message when it cannot. */
    fun start(profile: Profile, settings: DesktopSettings, rules: List<RoutingRule> = emptyList()) {
        stop()
        this.settings = settings
        lastError = null

        val tunInterface = if (settings.tunEnabled) TunSession.primaryInterface() else null
        val config = DesktopConfigBuilder.build(profile, settings, rules, tunInterface)

        // External engines have to be listening before the core tries to dial
        // into their local SOCKS inbound.
        try {
            plugins.start(config.plugins) { port, process, what ->
                awaitPort(port, process, timeoutMillis = 20_000, what = what)
            }
        } catch (e: Exception) {
            stop()
            throw e
        }

        startCore(profile, config.json, settings)

        if (tunInterface != null) {
            val session = TunSession { line -> log(line) }
            try {
                session.start(tunInterface, settings.tunMtu, settings.socksPort, settings.tunInterface)
            } catch (e: Exception) {
                stop()
                throw e
            }
            tunSession = session
            log("transparent mode ready on ${session.device}")
        }
    }

    private fun startCore(profile: Profile, config: String, settings: DesktopSettings) {
        val binary = DesktopRuntime.coreBinary()
            ?: throw UnsupportedProfileException("owenclave-core is not available for ${DesktopRuntime.platformTag}")

        coreConfigFile = File(DesktopRuntime.dataDir, "core.json")
        coreConfigFile.writeText(config)
        log("core config written to ${coreConfigFile.absolutePath}")

        log("starting ${binary.name}")
        val process = ProcessBuilder(binary.absolutePath, "run", "-c", coreConfigFile.absolutePath)
            .directory(DesktopRuntime.dataDir)
            .redirectErrorStream(true)
            .start()
        coreProcess = process
        pumpOutput("core", process)

        // The core is ready when the local SOCKS inbound accepts connections.
        try {
            awaitPort(settings.socksPort, process, timeoutMillis = 20_000, what = "core")
        } catch (e: Exception) {
            process.destroyForcibly()
            coreProcess = null
            throw e
        }

        running = true
        DataStore.startedProfile = profile.id.hashCode().toLong()
        SagerNet.started = true
        log("connected: ${profile.displayName} (${profile.protocolName})")

        // Watch for unexpected exits.
        Thread {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            if (running) {
                running = false
                SagerNet.started = false
                DataStore.startedProfile = 0L
                lastError = "core exited with code $code"
                log("core exited unexpectedly with code $code")
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        SagerNet.started = false
        DataStore.startedProfile = 0L

        // The routes and the device have to go before the core disappears.
        tunSession?.let { session ->
            log("stopping transparent mode")
            session.stop()
        }
        tunSession = null

        plugins.stop()

        coreProcess?.let { process ->
            log("stopping core")
            process.destroy()
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }
        coreProcess = null
    }

    /**
     * Fetches [url] through the local SOCKS inbound of the running core, which
     * proves the whole chain (client -> core -> upstream proxy) works.
     */
    fun fetch(url: String, timeoutMillis: Int = 15_000): HttpResult {
        val port = settings?.socksPort ?: throw IllegalStateException("core is not running")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Owenclave/${io.nekohasekai.sagernet.BuildConfig.VERSION_NAME}")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, body)
        } finally {
            connection.disconnect()
        }
    }

    fun testConnection(url: String = "https://www.gstatic.com/generate_204", timeoutMillis: Int = 15_000): String {
        val result = fetch(url, timeoutMillis)
        return "HTTP ${result.code} from $url"
    }

    data class HttpResult(val code: Int, val body: String)

    private fun pumpOutput(tag: String, process: Process) {
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line -> log("[$tag] $line") }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun awaitPort(port: Int, process: Process, timeoutMillis: Long, what: String) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw UnsupportedProfileException("$what terminated during startup (exit code ${process.exitValue()})")
            }
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
                return
            }
            Thread.sleep(150)
        }
        throw UnsupportedProfileException("$what did not start listening on 127.0.0.1:$port in time")
    }

    companion object {

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

    }

}
