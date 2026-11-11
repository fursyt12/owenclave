package io.nekohasekai.sagernet.desktop

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPrivilegedTunTest {

    private class FakeInteractiveProcess(
        private val exitCodeOnStop: Int = 0,
        private val immediateExitCode: Int? = null,
        private val stderrText: String = "",
        private val emitReadyOnHandshake: Boolean = true,
    ) : Process() {
        val receivedLines = CopyOnWriteArrayList<String>()
        private val stdoutPipeOut = PipedOutputStream()
        private val stdoutPipeIn = PipedInputStream(stdoutPipeOut, 8192)
        private val stdinPipeIn = PipedInputStream(8192)
        private val stdinPipeOut = PipedOutputStream(stdinPipeIn)
        private val doneLatch = CountDownLatch(1)

        @Volatile
        private var alive = true

        @Volatile
        private var code = exitCodeOnStop

        init {
            if (immediateExitCode != null) {
                code = immediateExitCode
                alive = false
                runCatching { stdoutPipeOut.close() }
                doneLatch.countDown()
            } else {
                Thread({
                    try {
                        stdinPipeIn.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            for (line in lines) {
                                receivedLines.add(line)
                                when {
                                    (line == "START" || line.startsWith("AUTH ")) && emitReadyOnHandshake -> {
                                        stdoutPipeOut.write(
                                            "OWENCLAVE_TUN_READY iface=wlan0 tun=owenclave-tun\n"
                                                .toByteArray(StandardCharsets.UTF_8)
                                        )
                                        stdoutPipeOut.flush()
                                    }
                                    line.startsWith("ADD_BYPASS ") || line == "ATTACH_CHILDREN" -> {
                                        stdoutPipeOut.write("OK\n".toByteArray(StandardCharsets.UTF_8))
                                        stdoutPipeOut.flush()
                                    }
                                    line == "STOP" -> {
                                        break
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        alive = false
                        runCatching { stdoutPipeOut.close() }
                        doneLatch.countDown()
                    }
                }, "fake-tun-helper").apply { isDaemon = true }.start()
            }
        }

        override fun getOutputStream(): OutputStream = stdinPipeOut
        override fun getInputStream(): InputStream = stdoutPipeIn
        override fun getErrorStream(): InputStream =
            ByteArrayInputStream(stderrText.toByteArray(StandardCharsets.UTF_8))

        override fun waitFor(): Int {
            doneLatch.await()
            return code
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            doneLatch.await(timeout, unit)

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("process has not exited")
            return code
        }

        override fun destroy() {
            alive = false
            runCatching { stdinPipeOut.close() }
            runCatching { stdoutPipeOut.close() }
            doneLatch.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    @Test
    fun `linux unprivileged start invokes pkexec, reuses session on reload, and stops without second prompt`() {
        val tempDir = Files.createTempDirectory("owenclave-tun-test").toFile()
        try {
            val helperFile = tempDir.resolve("owenclave-tun-helper").apply {
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }
            val tun2socksFile = tempDir.resolve("tun2socks").apply {
                writeText("bin")
                setExecutable(true)
            }
            val pkexecFile = tempDir.resolve("pkexec").apply {
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }

            val spawnedCommands = CopyOnWriteArrayList<List<String>>()
            var fakeProc: FakeInteractiveProcess? = null

            val launcher = object : PrivilegedProcessLauncher {
                override fun start(command: List<String>, env: Map<String, String>): Process {
                    spawnedCommands.add(command)
                    return FakeInteractiveProcess().also { fakeProc = it }
                }

                override fun findExecutable(name: String) =
                    if (name == "pkexec") pkexecFile else null

                override fun currentUid(): Int = 1000
                override fun isWindowsAdmin(): Boolean = false
            }

            val tun = DesktopPrivilegedTun(
                os = "linux",
                dataDir = tempDir,
                launcher = launcher,
                helperLocator = { helperFile },
                tun2socksLocator = { tun2socksFile },
                authTimeoutMs = 5_000L,
            )

            tun.start(
                PrivilegedTunParams(
                    owenclavePid = 4242L,
                    socksPort = 2080,
                    socksUsername = "u1",
                    socksPassword = "p1",
                    localDnsPort = 6450,
                    mtu = 1500,
                    enableIpv6 = false,
                    bypassLan = true,
                    bypassServerIps = listOf("198.51.100.10"),
                )
            )

            assertTrue(tun.isRunning())
            assertEquals(1, spawnedCommands.size)
            val cmd = spawnedCommands.single()
            assertEquals(pkexecFile.absolutePath, cmd[0])
            assertEquals(helperFile.absolutePath, cmd[1])
            assertTrue(cmd.containsAll(listOf("--pid", "4242", "--socks-port", "2080", "--dns-port", "6450")))
            assertTrue(cmd.containsAll(listOf("--bypass-ip", "198.51.100.10")))
            // Credentials must NEVER be leaked via command-line arguments:
            assertFalse(cmd.any { it.contains("u1") || it.contains("p1") })

            val expectedAuth = "AUTH " +
                Base64.getEncoder().encodeToString("u1".toByteArray()) + " " +
                Base64.getEncoder().encodeToString("p1".toByteArray())
            assertTrue(fakeProc!!.receivedLines.contains(expectedAuth))

            // Simulate profile switch (SagerNet.reloadService): same ports/MTU, new server IP.
            tun.start(
                PrivilegedTunParams(
                    owenclavePid = 4242L,
                    socksPort = 2080,
                    socksUsername = "u1",
                    socksPassword = "p1",
                    localDnsPort = 6450,
                    mtu = 1500,
                    enableIpv6 = false,
                    bypassLan = true,
                    bypassServerIps = listOf("198.51.100.10", "203.0.113.25"),
                )
            )
            // Must NOT spawn a second pkexec prompt!
            assertEquals(1, spawnedCommands.size)
            Thread.sleep(50)
            assertTrue(fakeProc!!.receivedLines.contains("ADD_BYPASS 203.0.113.25"))
            assertTrue(fakeProc!!.receivedLines.contains("ATTACH_CHILDREN"))

            tun.stop()
            assertFalse(tun.isRunning())
            assertTrue(fakeProc!!.receivedLines.contains("STOP"))
            assertEquals(1, spawnedCommands.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `polkit dialog dismissal throws TunAuthorizationException instead of silently succeeding`() {
        val tempDir = Files.createTempDirectory("owenclave-tun-cancel").toFile()
        try {
            val helperFile = tempDir.resolve("owenclave-tun-helper").apply { writeText(""); setExecutable(true) }
            val tun2socksFile = tempDir.resolve("tun2socks").apply { writeText(""); setExecutable(true) }
            val pkexecFile = tempDir.resolve("pkexec").apply { writeText(""); setExecutable(true) }

            val launcher = object : PrivilegedProcessLauncher {
                override fun start(command: List<String>, env: Map<String, String>): Process =
                    FakeInteractiveProcess(
                        immediateExitCode = 126,
                        stderrText = "Error executing command as another user: Request dismissed\n",
                    )

                override fun findExecutable(name: String) = if (name == "pkexec") pkexecFile else null
                override fun currentUid(): Int = 1000
                override fun isWindowsAdmin(): Boolean = false
            }

            val tun = DesktopPrivilegedTun(
                os = "linux",
                dataDir = tempDir,
                launcher = launcher,
                helperLocator = { helperFile },
                tun2socksLocator = { tun2socksFile },
                authTimeoutMs = 5_000L,
            )

            val err = assertFailsWith<TunAuthorizationException> {
                tun.start(PrivilegedTunParams(socksPort = 2080))
            }
            assertTrue(err.message.orEmpty().contains("cancelled", ignoreCase = true))
            assertFalse(tun.isRunning())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `falls back to sudo graphical askpass when no polkit agent is running`() {
        val tempDir = Files.createTempDirectory("owenclave-tun-askpass").toFile()
        try {
            val helperFile = tempDir.resolve("owenclave-tun-helper").apply { writeText(""); setExecutable(true) }
            val tun2socksFile = tempDir.resolve("tun2socks").apply { writeText(""); setExecutable(true) }
            val pkexecFile = tempDir.resolve("pkexec").apply { writeText(""); setExecutable(true) }
            val sudoFile = tempDir.resolve("sudo").apply { writeText(""); setExecutable(true) }
            val askpassFile = tempDir.resolve("ksshaskpass").apply { writeText(""); setExecutable(true) }

            val calls = CopyOnWriteArrayList<Pair<List<String>, Map<String, String>>>()

            val launcher = object : PrivilegedProcessLauncher {
                override fun start(command: List<String>, env: Map<String, String>): Process {
                    calls.add(command to env)
                    return if (command.first() == pkexecFile.absolutePath) {
                        FakeInteractiveProcess(
                            immediateExitCode = 127,
                            stderrText = "Error executing command as another user: No authentication agent found.\n",
                        )
                    } else {
                        FakeInteractiveProcess()
                    }
                }

                override fun findExecutable(name: String) = when (name) {
                    "pkexec" -> pkexecFile
                    "sudo" -> sudoFile
                    "ksshaskpass" -> askpassFile
                    else -> null
                }

                override fun currentUid(): Int = 1000
                override fun isWindowsAdmin(): Boolean = false
            }

            val tun = DesktopPrivilegedTun(
                os = "linux",
                dataDir = tempDir,
                launcher = launcher,
                helperLocator = { helperFile },
                tun2socksLocator = { tun2socksFile },
                authTimeoutMs = 5_000L,
            )

            tun.start(PrivilegedTunParams(socksPort = 2080))
            assertTrue(tun.isRunning())
            assertEquals(2, calls.size)
            assertEquals(sudoFile.absolutePath, calls[1].first[0])
            assertEquals("-A", calls[1].first[1])
            assertEquals(askpassFile.absolutePath, calls[1].second["SUDO_ASKPASS"])
            tun.stop()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `bundled linux helper script has valid POSIX shell syntax`() {
        val devRoot = System.getProperty("owenclave.devRoot") ?: return
        val script = java.io.File(devRoot, "packaging/linux/owenclave-tun-helper")
        assertTrue(script.isFile, "Expected packaging/linux/owenclave-tun-helper to exist")
        val shCheck = ProcessBuilder("sh", "-n", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = shCheck.inputStream.bufferedReader().readText()
        assertEquals(0, shCheck.waitFor(), "sh -n failed: $out")
    }

    @Test
    fun `log shim rotates previous session logcat and classifies naive chromium stderr levels`() {
        val tempDir = Files.createTempDirectory("owenclave-log-test").toFile()
        try {
            val logcat = tempDir.resolve("logcat.log")
            logcat.writeText("11-06 11:17:58.128 E/Owenclave: stale crash from previous session\n")

            val active = Log.rotateSessionLogFile(tempDir)
            assertEquals("", active.readText())
            val prev = tempDir.resolve("logcat.prev.log")
            assertTrue(prev.isFile)
            assertTrue(prev.readText().contains("stale crash from previous session"))

            assertEquals(
                'I',
                Log.classifyStderrLevel("[1111/112349.123456:INFO:naive_proxy_bin.cc(120)] Proxying via HTTPS example.com:443"),
            )
            assertEquals(
                'W',
                Log.classifyStderrLevel("[1111/112349.123456:WARNING:cert_verify_proc.cc(80)] Warning detail"),
            )
            assertEquals(
                'E',
                Log.classifyStderrLevel("[1111/112349.123456:ERROR:ssl_client_socket_impl.cc(990)] handshake failed"),
            )

            val captured = CopyOnWriteArrayList<String>()
            val listener: (String) -> Unit = { captured.add(it) }
            Log.addListener(listener)
            try {
                Log.e("naive", "[1111/112349.123456:INFO:socks5_server_socket.cc(90)] Connection 1 established")
                assertTrue(captured.any { it.contains("I/naive:") }, "Expected INFO stderr line to be emitted as I/naive:")
                assertFalse(captured.any { it.contains("E/naive:") }, "Expected INFO stderr line not to be emitted as E/naive:")
            } finally {
                Log.removeListener(listener)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
