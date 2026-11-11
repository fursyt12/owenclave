package io.nekohasekai.sagernet.desktop

import android.util.Log
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Raised when administrator/Polkit authorization for VPN mode is cancelled or unavailable. */
class TunAuthorizationException(message: String, cause: Throwable? = null) :
    TunException(message, cause),
    BaseService.ExpectedException

/** Parameters describing the desired desktop VPN / TUN session. */
data class PrivilegedTunParams(
    val owenclavePid: Long = ProcessHandle.current().pid(),
    val socksPort: Int,
    val socksUsername: String = "",
    val socksPassword: String = "",
    val localDnsPort: Int = 0,
    val mtu: Int = 1500,
    val tunDevice: String = TunSession.DEFAULT_LINUX_DEVICE,
    val enableIpv6: Boolean = false,
    val bypassLan: Boolean = true,
    val bypassServerIps: List<String> = emptyList(),
) {
    internal data class TunnelKey(
        val socksPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val localDnsPort: Int,
        val mtu: Int,
        val tunDevice: String,
        val enableIpv6: Boolean,
        val bypassLan: Boolean,
    )

    internal val key: TunnelKey
        get() = TunnelKey(
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            localDnsPort = localDnsPort,
            mtu = mtu,
            tunDevice = tunDevice,
            enableIpv6 = enableIpv6,
            bypassLan = bypassLan,
        )
}

/** Abstraction over OS process spawning so privilege-elevation flows are 100% unit-testable. */
interface PrivilegedProcessLauncher {
    fun start(command: List<String>, env: Map<String, String> = emptyMap()): Process
    fun findExecutable(name: String): File?
    fun currentUid(): Int
    fun isWindowsAdmin(): Boolean
}

object SystemPrivilegedProcessLauncher : PrivilegedProcessLauncher {
    override fun start(command: List<String>, env: Map<String, String>): Process {
        val pb = ProcessBuilder(command)
        if (env.isNotEmpty()) {
            pb.environment().putAll(env)
        }
        return pb.start()
    }

    override fun findExecutable(name: String): File? {
        val pathEnv = System.getenv("PATH").orEmpty()
        val dirs = pathEnv.split(File.pathSeparatorChar).filter { it.isNotBlank() } +
            listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin")
        val names = if (DesktopRuntime.isWindows && !name.endsWith(".exe", ignoreCase = true)) {
            listOf("$name.exe", name)
        } else {
            listOf(name)
        }
        for (dir in dirs) {
            for (candidateName in names) {
                val f = File(dir, candidateName)
                if (f.isFile && f.canExecute()) return f
            }
        }
        return null
    }

    override fun currentUid(): Int {
        if (DesktopRuntime.isWindows) return -1
        return runCatching {
            val proc = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            out.toIntOrNull() ?: -1
        }.getOrDefault(-1)
    }

    override fun isWindowsAdmin(): Boolean {
        if (!DesktopRuntime.isWindows) return false
        return runCatching {
            val proc = ProcessBuilder("net", "session").redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
        }.getOrDefault(false)
    }
}

/**
 * Manages the elevated desktop VPN (`tun2socks` + OS routes + loop-free socket bypass)
 * for `ComposeMainActivity` / `ProxyService` when `DataStore.serviceMode == Key.MODE_VPN`.
 *
 * - **Linux**: Launches `owenclave-tun-helper` via `pkexec` (Polkit graphical dialog)
 *   when unprivileged, or directly when `uid == 0` (with graphical `sudo -A` askpass
 *   fallback when no Polkit agent is running). Keeps a single elevated supervisor
 *   open across profile reloads and tears down cleanly on `STOP` or EOF without
 *   prompting a second time.
 * - **macOS**: Prompts via `osascript ... with administrator privileges` when unprivileged.
 * - **Windows**: Prompts via UAC (`Start-Process -Verb RunAs`) when not Administrator.
 */
class DesktopPrivilegedTun(
    private val os: String = DesktopRuntime.os,
    private val dataDir: File = DesktopRuntime.dataDir,
    private val launcher: PrivilegedProcessLauncher = SystemPrivilegedProcessLauncher,
    private val helperLocator: () -> File? = { DesktopRuntime.tunHelperScript() },
    private val tun2socksLocator: () -> File? = { DesktopRuntime.tun2socksBinary() },
    private val authTimeoutMs: Long = 120_000L,
) {

    private val lock = Any()
    private var supervisorProcess: Process? = null
    private var supervisorStdin: BufferedWriter? = null
    private var keepaliveFile: File? = null
    private var directWinTun: TunSession? = null
    private var activeKey: PrivilegedTunParams.TunnelKey? = null
    private val registeredBypassIps = LinkedHashSet<String>()

    fun isRunning(): Boolean = synchronized(lock) {
        when {
            supervisorProcess != null -> supervisorProcess?.isAlive == true
            directWinTun != null -> directWinTun?.running == true
            keepaliveFile != null -> keepaliveFile?.isFile == true
            else -> false
        }
    }

    /**
     * Ensures the TUN session is running with [params]. If an identical session is
     * already up (e.g. during a profile switch via `SagerNet.reloadService()`),
     * updates server bypass routes and re-attaches child processes in place without
     * dropping the tunnel or re-prompting for administrator authorization.
     */
    fun start(params: PrivilegedTunParams) {
        synchronized(lock) {
            if (isRunning() && activeKey == params.key) {
                val stdin = supervisorStdin
                if (os == "linux" && stdin != null) {
                    val ok = runCatching {
                        for (ip in params.bypassServerIps) {
                            if (registeredBypassIps.add(ip)) {
                                stdin.write("ADD_BYPASS $ip\n")
                            }
                        }
                        stdin.write("ATTACH_CHILDREN\n")
                        stdin.flush()
                        true
                    }.getOrDefault(false)
                    if (ok) {
                        Log.i(TAG, "Reused active VPN tunnel for reloaded profile")
                        return
                    }
                } else if (keepaliveFile?.isFile == true || directWinTun?.running == true) {
                    return
                }
            }

            stopLocked()

            when (os) {
                "linux" -> startLinuxLocked(params)
                "darwin" -> startMacLocked(params)
                "windows" -> startWindowsLocked(params)
                else -> throw TunException("Unsupported desktop OS for VPN mode: $os")
            }
            activeKey = params.key
            registeredBypassIps.clear()
            registeredBypassIps.addAll(params.bypassServerIps)
        }
    }

    /** Tears down the active TUN session, routes, and helper process. Idempotent. */
    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        activeKey = null
        registeredBypassIps.clear()

        keepaliveFile?.let { f ->
            runCatching { f.delete() }
        }
        keepaliveFile = null

        directWinTun?.let { tun ->
            runCatching { tun.stop() }
        }
        directWinTun = null

        val stdin = supervisorStdin
        val proc = supervisorProcess
        supervisorStdin = null
        supervisorProcess = null

        if (stdin != null) {
            runCatching {
                stdin.write("STOP\n")
                stdin.flush()
                stdin.close()
            }
        }
        if (proc != null) {
            if (!runCatching { proc.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)) {
                runCatching { proc.destroy() }
                if (!runCatching { proc.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
                    runCatching { proc.destroyForcibly() }
                }
            }
        }
    }

    // ------------------------------------------------------------------- Linux

    internal fun buildLinuxHelperArgs(
        helperFile: File,
        tun2socksFile: File,
        params: PrivilegedTunParams,
    ): List<String> {
        val args = ArrayList<String>()
        args += helperFile.absolutePath
        args += listOf("--pid", params.owenclavePid.toString())
        args += listOf("--socks-port", params.socksPort.toString())
        args += listOf("--dns-port", params.localDnsPort.coerceAtLeast(0).toString())
        args += listOf("--mtu", params.mtu.coerceIn(1280, 9000).toString())
        args += listOf("--tun-dev", params.tunDevice)
        args += listOf("--ipv6", if (params.enableIpv6) "1" else "0")
        args += listOf("--bypass-lan", if (params.bypassLan) "1" else "0")
        args += listOf("--tun2socks", tun2socksFile.absolutePath)
        for (ip in params.bypassServerIps) {
            val trimmed = ip.trim()
            if (trimmed.isNotEmpty()) {
                args += listOf("--bypass-ip", trimmed)
            }
        }
        return args
    }

    private fun startLinuxLocked(params: PrivilegedTunParams) {
        val tun2socks = tun2socksLocator()
            ?: throw TunException("tun2socks binary is missing for ${DesktopRuntime.platformTag}")
        val helper = helperLocator()
            ?: throw TunException("owenclave-tun-helper is missing from the installation")
        runCatching { helper.setExecutable(true, false) }
        runCatching { tun2socks.setExecutable(true, false) }

        val helperArgs = buildLinuxHelperArgs(helper, tun2socks, params)
        val uid = launcher.currentUid()

        if (uid == 0) {
            launchLinuxSupervisor(helperArgs, emptyMap(), params)
            return
        }

        val pkexec = launcher.findExecutable("pkexec")
        if (pkexec != null) {
            try {
                launchLinuxSupervisor(listOf(pkexec.absolutePath) + helperArgs, emptyMap(), params)
                return
            } catch (e: NoPolkitAgentException) {
                Log.w(TAG, "Polkit agent unavailable (${e.message}), checking graphical askpass fallback")
            }
        }

        val sudoAskpass = resolveLinuxAskpassCommand()
        val sudo = launcher.findExecutable("sudo")
        if (sudo != null && sudoAskpass != null) {
            launchLinuxSupervisor(
                command = listOf(sudo.absolutePath, "-A", "-k") + helperArgs,
                env = mapOf("SUDO_ASKPASS" to sudoAskpass.absolutePath),
                params = params,
            )
            return
        }

        if (pkexec == null) {
            throw TunAuthorizationException(
                "pkexec (Polkit) is not installed; install polkit or switch Service Mode to Proxy"
            )
        }
        throw TunAuthorizationException(
            "No Polkit authentication agent is running in this desktop session " +
                "(start polkit-kde-agent, polkit-gnome, or hyprpolkitagent)"
        )
    }

    private class NoPolkitAgentException(message: String) : Exception(message)

    private fun launchLinuxSupervisor(
        command: List<String>,
        env: Map<String, String>,
        params: PrivilegedTunParams,
    ) {
        Log.i(TAG, "Requesting privileged TUN setup: ${command.firstOrNull()}")
        val process = try {
            launcher.start(command, env)
        } catch (e: Exception) {
            throw TunException("Cannot launch privileged helper: ${e.message}", e)
        }

        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        val readyLatch = CountDownLatch(1)
        val readyLine = AtomicReference<String?>(null)
        val stderrLines = Collections.synchronizedList(ArrayList<String>())

        val handshake = if (params.socksUsername.isNotEmpty() || params.socksPassword.isNotEmpty()) {
            val u64 = Base64.getEncoder().encodeToString(params.socksUsername.toByteArray(StandardCharsets.UTF_8))
            val p64 = Base64.getEncoder().encodeToString(params.socksPassword.toByteArray(StandardCharsets.UTF_8))
            "AUTH $u64 $p64\n"
        } else {
            "START\n"
        }

        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        if (stderrLines.size < 40) stderrLines.add(line)
                        Log.i("OwenclaveTUN", line)
                    }
                }
            }
        }, "owenclave-tun-stderr").apply { isDaemon = true }.start()

        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("OWENCLAVE_TUN_READY")) {
                            readyLine.set(line)
                            readyLatch.countDown()
                        } else if (line != "OK") {
                            Log.i("OwenclaveTUN", line)
                        }
                    }
                }
            }
            readyLatch.countDown()
        }, "owenclave-tun-stdout").apply { isDaemon = true }.start()

        runCatching {
            writer.write(handshake)
            writer.flush()
        }

        val completed = readyLatch.await(authTimeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            runCatching { writer.close() }
            runCatching { process.destroyForcibly() }
            throw TunAuthorizationException("VPN authorization timed out")
        }

        val ready = readyLine.get()
        if (ready != null && process.isAlive) {
            supervisorProcess = process
            supervisorStdin = writer
            Log.i(TAG, "VPN tunnel active ($ready)")
            return
        }

        runCatching { writer.close() }
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        val exitCode = if (process.isAlive) {
            runCatching { process.destroyForcibly() }
            -1
        } else {
            process.exitValue()
        }
        val combinedErr = synchronized(stderrLines) { stderrLines.joinToString("; ") }

        if (combinedErr.contains("No authentication agent found", ignoreCase = true)) {
            throw NoPolkitAgentException(combinedErr)
        }
        if (exitCode == 126 ||
            combinedErr.contains("Request dismissed", ignoreCase = true) ||
            combinedErr.contains("cancelled", ignoreCase = true) ||
            combinedErr.contains("canceled", ignoreCase = true)
        ) {
            throw TunAuthorizationException("VPN authorization was cancelled")
        }
        if (exitCode == 127 || combinedErr.contains("Not authorized", ignoreCase = true)) {
            throw TunAuthorizationException(
                "VPN authorization failed" + if (combinedErr.isNotBlank()) ": $combinedErr" else ""
            )
        }
        throw TunException(
            "Failed to start VPN tunnel" +
                if (combinedErr.isNotBlank()) ": $combinedErr" else " (exit code $exitCode)"
        )
    }

    private fun resolveLinuxAskpassCommand(): File? {
        val knownAskpass = listOf(
            "ksshaskpass",
            "lxqt-openssh-askpass",
            "ssh-askpass",
            "/usr/lib/ssh/ssh-askpass",
            "/usr/libexec/openssh/ssh-askpass",
            "/usr/lib/seahorse/ssh-askpass",
        )
        for (candidate in knownAskpass) {
            val f = if (candidate.startsWith("/")) {
                File(candidate).takeIf { it.isFile && it.canExecute() }
            } else {
                launcher.findExecutable(candidate)
            }
            if (f != null) return f
        }

        val zenity = launcher.findExecutable("zenity")
        if (zenity != null) {
            val script = File(File(dataDir, "runtime").apply { mkdirs() }, "owenclave-askpass.sh")
            script.writeText(
                "#!/bin/sh\nexec \"${zenity.absolutePath}\" --password --title=\"Owenclave VPN\"\n"
            )
            script.setExecutable(true, true)
            return script
        }

        val kdialog = launcher.findExecutable("kdialog")
        if (kdialog != null) {
            val script = File(File(dataDir, "runtime").apply { mkdirs() }, "owenclave-askpass.sh")
            script.writeText(
                "#!/bin/sh\nexec \"${kdialog.absolutePath}\" --title \"Owenclave VPN\" --password \"Требуется пароль администратора для запуска VPN-туннеля Owenclave:\"\n"
            )
            script.setExecutable(true, true)
            return script
        }

        return null
    }

    // ------------------------------------------------------------------- macOS

    private fun startMacLocked(params: PrivilegedTunParams) {
        val tun2socks = tun2socksLocator()
            ?: throw TunException("tun2socks binary is missing for ${DesktopRuntime.platformTag}")
        runCatching { tun2socks.setExecutable(true, false) }

        val runtimeDir = File(dataDir, "runtime").apply { mkdirs() }
        val token = System.nanoTime().toString()
        val keepalive = File(runtimeDir, "owenclave-tun-$token.keepalive").apply { writeText("1") }
        val statusFile = File(runtimeDir, "owenclave-tun-$token.status").apply { delete() }
        val scriptFile = File(runtimeDir, "owenclave-tun-macos.sh")

        val bypassArray = params.bypassServerIps
            .filter { it.matches(Regex("^[0-9a-fA-F:.]+$")) }
            .joinToString(" ")

        scriptFile.writeText(
            """
            #!/bin/sh
            set -eu
            KEEPALIVE="${keepalive.absolutePath}"
            STATUS="${statusFile.absolutePath}"
            TUN2SOCKS="${tun2socks.absolutePath}"
            OWNER_PID="${params.owenclavePid}"
            SOCKS_PORT="${params.socksPort}"
            MTU="${params.mtu.coerceIn(1280, 9000)}"
            BYPASS_IPS="$bypassArray"
            T2S_PID=""
            UTUN=""
            PHYS_GW=""

            cleanup() {
              for r in 1.0.0.0/8 2.0.0.0/7 4.0.0.0/6 8.0.0.0/5 16.0.0.0/4 32.0.0.0/3 64.0.0.0/2 128.0.0.0/1 198.18.0.0/15; do
                [ -n "${'$'}UTUN" ] && route -n delete -net "${'$'}r" -interface "${'$'}UTUN" >/dev/null 2>&1 || true
              done
              for ip in ${'$'}BYPASS_IPS; do
                [ -n "${'$'}PHYS_GW" ] && route -n delete -host "${'$'}ip" "${'$'}PHYS_GW" >/dev/null 2>&1 || true
              done
              [ -n "${'$'}T2S_PID" ] && kill "${'$'}T2S_PID" >/dev/null 2>&1 || true
              rm -f "${'$'}KEEPALIVE" "${'$'}STATUS" >/dev/null 2>&1 || true
            }
            trap cleanup EXIT INT TERM HUP

            DEF_OUT="${'$'}(route -n get default 2>/dev/null || true)"
            PHYS_IF="${'$'}(printf '%s\n' "${'$'}DEF_OUT" | awk '/interface:/{print ${'$'}2; exit}')"
            PHYS_GW="${'$'}(printf '%s\n' "${'$'}DEF_OUT" | awk '/gateway:/{print ${'$'}2; exit}')"
            if [ -z "${'$'}PHYS_IF" ]; then
              printf 'ERROR:cannot detect default network interface\n' >"${'$'}STATUS"
              exit 1
            fi

            EXISTING="${'$'}(ifconfig -l 2>/dev/null || true)"
            i=0
            while [ "${'$'}i" -lt 32 ]; do
              cand="utun${'$'}i"
              case " ${'$'}EXISTING " in
                *" ${'$'}cand "*) i=${'$'}((i + 1)) ;;
                *) UTUN="${'$'}cand"; break ;;
              esac
            done
            [ -z "${'$'}UTUN" ] && UTUN="utun32"

            for ip in ${'$'}BYPASS_IPS; do
              [ -n "${'$'}PHYS_GW" ] && route -n add -host "${'$'}ip" "${'$'}PHYS_GW" >/dev/null 2>&1 || true
            done

            "${'$'}TUN2SOCKS" --device "${'$'}UTUN" --proxy "socks5://127.0.0.1:${'$'}SOCKS_PORT" --interface "${'$'}PHYS_IF" --loglevel warn &
            T2S_PID="${'$'}!"

            j=0
            while [ "${'$'}j" -lt 50 ]; do
              ifconfig "${'$'}UTUN" >/dev/null 2>&1 && break
              sleep 0.2
              j=${'$'}((j + 1))
            done

            ifconfig "${'$'}UTUN" 172.19.0.1 172.19.0.2 mtu "${'$'}MTU" up
            for r in 1.0.0.0/8 2.0.0.0/7 4.0.0.0/6 8.0.0.0/5 16.0.0.0/4 32.0.0.0/3 64.0.0.0/2 128.0.0.0/1 198.18.0.0/15; do
              route -n add -net "${'$'}r" -interface "${'$'}UTUN" >/dev/null
            done

            printf 'OWENCLAVE_TUN_READY tun=%s\n' "${'$'}UTUN" >"${'$'}STATUS"
            while [ -f "${'$'}KEEPALIVE" ] && kill -0 "${'$'}OWNER_PID" 2>/dev/null && kill -0 "${'$'}T2S_PID" 2>/dev/null; do
              sleep 0.3
            done
            """.trimIndent() + "\n"
        )
        scriptFile.setExecutable(true, true)

        val command = if (launcher.currentUid() == 0) {
            listOf("/bin/sh", scriptFile.absolutePath)
        } else {
            val escaped = scriptFile.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
            listOf(
                "/usr/bin/osascript",
                "-e",
                "do shell script \"\\\"$escaped\\\" >/dev/null 2>&1 &\" with administrator privileges",
            )
        }

        val proc = launcher.start(command)
        val errHolder = AtomicReference("")
        Thread({
            runCatching {
                errHolder.set(proc.errorStream.bufferedReader().use { it.readText() }.trim())
            }
        }, "owenclave-tun-macos").apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + authTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (statusFile.isFile) {
                val status = runCatching { statusFile.readText().trim() }.getOrDefault("")
                if (status.startsWith("OWENCLAVE_TUN_READY")) {
                    keepaliveFile = keepalive
                    if (launcher.currentUid() == 0) supervisorProcess = proc
                    Log.i(TAG, "macOS VPN tunnel active ($status)")
                    return
                }
                if (status.startsWith("ERROR:")) {
                    keepalive.delete()
                    throw TunException(status.removePrefix("ERROR:"))
                }
            }
            if (!proc.isAlive && proc.exitValue() != 0) {
                keepalive.delete()
                val err = errHolder.get()
                if (err.contains("User canceled", ignoreCase = true) || err.contains("-128")) {
                    throw TunAuthorizationException("VPN authorization was cancelled")
                }
                throw TunAuthorizationException("Failed to authorize macOS VPN tunnel: $err")
            }
            Thread.sleep(150)
        }
        keepalive.delete()
        throw TunAuthorizationException("VPN authorization timed out")
    }

    // ----------------------------------------------------------------- Windows

    private fun startWindowsLocked(params: PrivilegedTunParams) {
        val tun2socks = tun2socksLocator()
            ?: throw TunException("tun2socks.exe is missing for ${DesktopRuntime.platformTag}")

        if (launcher.isWindowsAdmin()) {
            val session = TunSession { line -> Log.i("OwenclaveTUN", line) }
            session.start(
                primaryInterface = TunSession.primaryInterface(),
                mtu = params.mtu,
                socksPort = params.socksPort,
                requestedDevice = params.tunDevice,
                ipv6 = params.enableIpv6,
            )
            directWinTun = session
            return
        }

        val runtimeDir = File(dataDir, "runtime").apply { mkdirs() }
        val token = System.nanoTime().toString()
        val keepalive = File(runtimeDir, "owenclave-tun-$token.keepalive").apply { writeText("1") }
        val statusFile = File(runtimeDir, "owenclave-tun-$token.status").apply { delete() }
        val psScript = File(runtimeDir, "owenclave-tun-win.ps1")
        val bypassIpsLiteral = params.bypassServerIps
            .filter { it.matches(Regex("^[0-9.]+$")) }
            .joinToString(",") { "'$it'" }

        psScript.writeText(
            """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}keepalive = '${keepalive.absolutePath.replace("'", "''")}'
            ${'$'}statusFile = '${statusFile.absolutePath.replace("'", "''")}'
            ${'$'}tun2socks = '${tun2socks.absolutePath.replace("'", "''")}'
            ${'$'}tunName = '${params.tunDevice.replace("'", "''")}'
            ${'$'}socksPort = ${params.socksPort}
            ${'$'}ownerPid = ${params.owenclavePid}
            ${'$'}bypassIps = @($bypassIpsLiteral)
            ${'$'}t2s = ${'$'}null
            ${'$'}gw = ''
            try {
              ${'$'}defRoute = Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric, InterfaceMetric | Select-Object -First 1
              ${'$'}physIf = ${'$'}defRoute.InterfaceAlias
              ${'$'}gw = ${'$'}defRoute.NextHop
              foreach (${'$'}ip in ${'$'}bypassIps) {
                if (${'$'}gw -and ${'$'}gw -ne '0.0.0.0') {
                  & route.exe add "${'$'}ip" mask 255.255.255.255 "${'$'}gw" metric 1 | Out-Null
                }
              }
              ${'$'}t2s = Start-Process -FilePath ${'$'}tun2socks -ArgumentList @('--device', "wintun://${'$'}tunName", '--proxy', "socks5://127.0.0.1:${'$'}socksPort", '--interface', ${'$'}physIf, '--loglevel', 'warn') -WorkingDirectory ([System.IO.Path]::GetDirectoryName(${'$'}tun2socks)) -WindowStyle Hidden -PassThru
              for (${'$'}i = 0; ${'$'}i -lt 60; ${'$'}i++) {
                if ((& netsh interface show interface "name=${'$'}tunName" 2>${'$'}null)) { break }
                Start-Sleep -Milliseconds 200
              }
              & netsh interface ipv4 set address "name=${'$'}tunName" source=static addr=172.19.0.1 mask=255.255.255.252 | Out-Null
              & netsh interface ipv4 add route 0.0.0.0/1 "${'$'}tunName" 172.19.0.1 metric=1 | Out-Null
              & netsh interface ipv4 add route 128.0.0.0/1 "${'$'}tunName" 172.19.0.1 metric=1 | Out-Null
              Set-Content -Path ${'$'}statusFile -Value "OWENCLAVE_TUN_READY tun=${'$'}tunName"
              while ((Test-Path ${'$'}keepalive) -and (-not ${'$'}t2s.HasExited) -and (Get-Process -Id ${'$'}ownerPid -ErrorAction SilentlyContinue)) {
                Start-Sleep -Milliseconds 300
              }
            } catch {
              Set-Content -Path ${'$'}statusFile -Value ("ERROR:" + ${'$'}_.Exception.Message)
            } finally {
              & netsh interface ipv4 delete route 0.0.0.0/1 "${'$'}tunName" 2>${'$'}null | Out-Null
              & netsh interface ipv4 delete route 128.0.0.0/1 "${'$'}tunName" 2>${'$'}null | Out-Null
              foreach (${'$'}ip in ${'$'}bypassIps) {
                & route.exe delete "${'$'}ip" 2>${'$'}null | Out-Null
              }
              if (${'$'}t2s -and -not ${'$'}t2s.HasExited) { Stop-Process -Id ${'$'}t2s.Id -Force -ErrorAction SilentlyContinue }
              Remove-Item -Force -ErrorAction SilentlyContinue ${'$'}keepalive, ${'$'}statusFile
            }
            """.trimIndent()
        )

        val elevateCmd = "try { Start-Process -FilePath 'powershell.exe' " +
            "-ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File','${psScript.absolutePath.replace("'", "''")}') " +
            "-Verb RunAs -WindowStyle Hidden; exit 0 } catch { Write-Error ${'$'}_.Exception.Message; exit 126 }"

        val proc = launcher.start(
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", elevateCmd)
        )
        val errText = proc.errorStream.bufferedReader().use { it.readText() }.trim()
        proc.waitFor(authTimeoutMs, TimeUnit.MILLISECONDS)
        if (proc.exitValue() != 0) {
            keepalive.delete()
            if (proc.exitValue() == 126 || errText.contains("canceled", ignoreCase = true)) {
                throw TunAuthorizationException("VPN authorization was cancelled")
            }
            throw TunAuthorizationException("Failed to elevate VPN helper: $errText")
        }

        val deadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < deadline) {
            if (statusFile.isFile) {
                val status = runCatching { statusFile.readText().trim() }.getOrDefault("")
                if (status.startsWith("OWENCLAVE_TUN_READY")) {
                    keepaliveFile = keepalive
                    Log.i(TAG, "Windows VPN tunnel active ($status)")
                    return
                }
                if (status.startsWith("ERROR:")) {
                    keepalive.delete()
                    throw TunException(status.removePrefix("ERROR:"))
                }
            }
            Thread.sleep(150)
        }
        keepalive.delete()
        throw TunException("Timed out waiting for Windows Wintun adapter to initialize")
    }

    companion object {
        private const val TAG = "DesktopTUN"

        /**
         * Resolves remote proxy server hostnames from [proxy] into concrete IPv4/IPv6
         * addresses while the physical network route is still untouched.
         */
        fun resolveBypassServerIps(proxy: ProxyInstance?): List<String> {
            if (proxy == null) return emptyList()
            val hosts = LinkedHashSet<String>()
            runCatching {
                proxy.profile.requireBean().apply {
                    if (!serverAddress.isNullOrBlank()) hosts.add(serverAddress.trim())
                    if (!finalAddress.isNullOrBlank()) hosts.add(finalAddress.trim())
                }
            }
            if (proxy.isInitialized()) {
                for (indexEntity in proxy.config.index) {
                    for ((_, chainEntity) in indexEntity.chain) {
                        runCatching {
                            val b = chainEntity.requireBean()
                            if (!b.serverAddress.isNullOrBlank()) hosts.add(b.serverAddress.trim())
                            if (!b.finalAddress.isNullOrBlank()) hosts.add(b.finalAddress.trim())
                        }
                    }
                }
            }

            val ips = LinkedHashSet<String>()
            for (rawHost in hosts) {
                val host = rawHost.removeSurrounding("[", "]").trim()
                if (host.isEmpty() || host.equals("localhost", ignoreCase = true)) continue
                runCatching {
                    for (addr in InetAddress.getAllByName(host)) {
                        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress) continue
                        val ip = addr.hostAddress?.substringBefore('%')?.trim().orEmpty()
                        if (ip.isNotEmpty() && !ip.startsWith("198.18.") && !ip.startsWith("198.19.")) {
                            ips.add(ip)
                        }
                    }
                }
            }
            return ips.toList()
        }
    }
}
