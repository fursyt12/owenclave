package io.nekohasekai.sagernet.desktop

import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * End to end smoke test for the desktop runtime.
 *
 * It does not depend on any external server: the Go core is started a first time
 * as a local Shadowsocks *server*, then the desktop client connects to it through
 * a normal [Profile], and finally an HTTP request is issued through the local
 * SOCKS inbound. A successful run proves that
 *
 *   client -> core inbounds -> shared config builder -> core outbound -> upstream
 *
 * all work on the current platform, which is what makes this usable for Windows
 * (under Wine) and Linux verification.
 */
object SelfTest {

    private const val METHOD = "aes-256-gcm"
    private const val PASSWORD = "owenclave-desktop-selftest"

    private const val PAYLOAD = "owenclave-desktop-ok"

    fun run(): Int {
        val server = LocalHttpServer(PAYLOAD)
        val httpPort = server.port

        var serverProcess: Process? = null
        var client: CoreRunner? = null

        return try {
            val core = DesktopRuntime.coreBinary()
                ?: throw IllegalStateException("owenclave-core is not available for ${DesktopRuntime.platformTag}")
            println("[selftest] core: ${core.absolutePath}")

            // 1. local Shadowsocks server, served by the very same core binary
            val serverPort = CoreRunner.freePort()
            val serverConfig = File(DesktopRuntime.dataDir, "selftest-server.json")
            serverConfig.writeText(
                """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [
                    {
                      "tag": "ss-in",
                      "listen": "127.0.0.1",
                      "port": $serverPort,
                      "protocol": "shadowsocks",
                      "settings": { "method": "$METHOD", "password": "$PASSWORD", "network": "tcp,udp" }
                    }
                  ],
                  "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
                }
                """.trimIndent()
            )
            println("[selftest] starting local Shadowsocks server on 127.0.0.1:$serverPort")
            serverProcess = ProcessBuilder(core.absolutePath, "run", "-c", serverConfig.absolutePath)
                .directory(DesktopRuntime.dataDir)
                .redirectErrorStream(true)
                .start()
            val ssServer = serverProcess
            Thread {
                runCatching { ssServer.inputStream.bufferedReader().forEachLine { println("[ss-server] $it") } }
            }.apply { isDaemon = true }.start()
            awaitPort(serverPort)

            // 2. desktop client profile pointing at that server
            val bean = ShadowsocksBean().apply {
                this.serverAddress = "127.0.0.1"
                this.serverPort = serverPort
                this.method = METHOD
                this.password = PASSWORD
                this.name = "selftest"
            }
            bean.initializeDefaultValues()
            val profile = Profile(name = "selftest", bean = bean)

            val settings = DesktopSettings(
                socksPort = CoreRunner.freePort(),
                httpPort = CoreRunner.freePort(),
                logLevel = LogLevel.WARNING,
                // the test target is on loopback, so LAN bypass must be off for the
                // request to really traverse the Shadowsocks outbound
                bypassPrivateNetworks = false,
            )
            println("[selftest] client SOCKS inbound 127.0.0.1:${settings.socksPort}")
            client = CoreRunner { line -> println("[client] $line") }
            client.start(profile, settings)

            // 3. request through the proxy chain
            val url = "http://127.0.0.1:$httpPort/"
            println("[selftest] fetching $url through the SOCKS proxy")
            val result = client.fetch(url)
            val ok = result.code == 200 && result.body.trim() == PAYLOAD
            println("[selftest] response: HTTP ${result.code}, body='${result.body.trim()}'")
            if (ok) {
                println("[selftest] PASS")
                0
            } else {
                println("[selftest] FAIL: unexpected response")
                1
            }
        } catch (e: Exception) {
            println("[selftest] FAIL: ${e.javaClass.simpleName}: ${e.message}")
            1
        } finally {
            runCatching { client?.stop() }
            runCatching { serverProcess?.destroy() }
            server.stop()
        }
    }

    /**
     * Verifies the NaiveProxy plugin integration: the config produced by the
     * shared `buildNaiveConfig` has to be accepted by the real `naive` binary,
     * the plugin has to keep listening on its local SOCKS port and the core has
     * to start and dial into it.
     *
     * The upstream server is not a real NaiveProxy server, so traffic obviously
     * cannot flow; that part needs a live server and is out of scope here.
     */
    fun runNaive(): Int {
        val plugin = DesktopRuntime.naiveBinary()
        if (plugin == null) {
            println("[selftest-naive] FAIL: naive binary is not available for ${DesktopRuntime.platformTag}")
            return 1
        }
        println("[selftest-naive] plugin: ${plugin.absolutePath}")

        val bean = NaiveBean().apply {
            this.serverAddress = "127.0.0.1"
            this.serverPort = 8443
            this.proto = "https"
            this.username = "selftest-user"
            this.password = "selftest-pass"
            this.sni = "localhost"
            this.name = "naive-selftest"
        }
        bean.initializeDefaultValues()
        val profile = Profile(name = "naive-selftest", bean = bean)
        val settings = DesktopSettings(
            socksPort = CoreRunner.freePort(),
            httpPort = CoreRunner.freePort(),
            logLevel = LogLevel.INFO,
        )

        val runner = CoreRunner { line -> println("[client] $line") }
        return try {
            runner.start(profile, settings)
            Thread.sleep(1500)
            println("[selftest-naive] generated plugin config:")
            println(File(DesktopRuntime.dataDir, "naive.json").readText())
            val alive = runner.pluginAlive()
            println("[selftest-naive] plugin alive: $alive, core running: ${runner.running}")
            val attempt = runCatching { runner.fetch("http://example.com/", 8000) }
            println("[selftest-naive] tunnel attempt: " + (attempt.exceptionOrNull()?.message ?: "unexpectedly succeeded"))
            if (alive && runner.running) {
                println("[selftest-naive] PASS")
                0
            } else {
                println("[selftest-naive] FAIL: plugin or core is not running")
                1
            }
        } catch (e: Exception) {
            println("[selftest-naive] FAIL: ${e.javaClass.simpleName}: ${e.message}")
            1
        } finally {
            runCatching { runner.stop() }
        }
    }

    /**
     * Minimal HTTP server on a loopback [ServerSocket].
     *
     * Written by hand because the jlink runtime that jpackage produces does not
     * necessarily contain the `jdk.httpserver` module, and the self test has to
     * work inside the packaged application as well.
     */
    private class LocalHttpServer(private val payload: String) {

        private val socket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())

        val port: Int get() = socket.localPort

        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { connection ->
                        val reader = connection.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val body = payload.toByteArray()
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        connection.getOutputStream().apply {
                            write(head.toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun stop() {
            runCatching { socket.close() }
        }

    }

    private fun awaitPort(port: Int) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
            }.isSuccess
            if (connected) return
            Thread.sleep(150)
        }
        throw IllegalStateException("server did not start listening on $port")
    }

}
