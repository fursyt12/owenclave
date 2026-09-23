package io.nekohasekai.sagernet.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/** Raised when the transparent mode cannot be brought up. */
class TunException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Transparent (TUN) mode for the desktop client.
 *
 * The core CLI cannot create a TUN device in this fork (on Android the app creates
 * one through `VpnService` and hands the fd to the gomobile library), so desktop
 * transparent mode is assembled from two pieces:
 *
 *  1. the core keeps serving its local SOCKS inbound,
 *  2. `tun2socks` owns the TUN device and pumps it into that SOCKS port.
 *
 * The core's outbound is bound to the physical interface (`bindToDevice`, supported
 * on Linux/macOS/Windows by the core), so its own connections never re-enter the
 * tunnel. Routes, addresses and the device itself are set up with the platform
 * tools following the tun2socks documentation.
 *
 * Everything needs administrator rights: Linux `sudo` (or CAP_NET_ADMIN), macOS
 * `sudo`, Windows an elevated process.
 */
class TunSession(private val log: (String) -> Unit) {

    private var pump: Process? = null
    private val teardown = ArrayList<() -> Unit>()

    @Volatile
    var device: String = ""
        private set

    val running: Boolean get() = pump?.isAlive == true

    /** Starts the pump and configures the OS. Throws [TunException] with a reason. */
    fun start(primaryInterface: String, mtu: Int, socksPort: Int, requestedDevice: String = "") {
        val binary = DesktopRuntime.tun2socksBinary()
            ?: throw TunException(
                "tun2socks is not available for ${DesktopRuntime.platformTag}; " +
                    "run `./run desktop tun2socks download`"
            )
        if (DesktopRuntime.isWindows && !File(binary.parentFile, "wintun.dll").isFile) {
            throw TunException(
                "wintun.dll is missing next to ${binary.name}; download it from https://www.wintun.net/ " +
                    "and put it into ${binary.parentFile}"
            )
        }

        try {
            when (DesktopRuntime.os) {
                "windows" -> startWindows(binary, primaryInterface, mtu, socksPort)
                "darwin" -> startMac(binary, primaryInterface, mtu, socksPort)
                else -> startLinux(binary, primaryInterface, mtu, socksPort, requestedDevice)
            }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        for (action in teardown.asReversed()) {
            runCatching { action() }.onFailure { log("tun cleanup: ${it.message}") }
        }
        teardown.clear()
        pump?.let { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        pump = null
        device = ""
    }

    // ------------------------------------------------------------------ linux

    private fun startLinux(
        binary: File,
        primaryInterface: String,
        mtu: Int,
        socksPort: Int,
        requestedDevice: String,
    ) {
        val name = requestedDevice.ifBlank { DEFAULT_LINUX_DEVICE }
        device = name

        runQuiet("ip", "tuntap", "add", "mode", "tun", "dev", name)
        teardown.add { runQuiet("ip", "link", "delete", name) }

        run("ip", "addr", "add", TUN_ADDRESS, "dev", name)
        run("ip", "link", "set", "dev", name, "up", "mtu", mtu.toString())

        // Split defaults are more specific than the real default route, so the
        // original one stays untouched and simply takes over again on teardown.
        run("ip", "route", "add", "0.0.0.0/1", "dev", name, "metric", "1")
        teardown.add { runQuiet("ip", "route", "del", "0.0.0.0/1", "dev", name) }
        run("ip", "route", "add", "128.0.0.0/1", "dev", name, "metric", "1")
        teardown.add { runQuiet("ip", "route", "del", "128.0.0.0/1", "dev", name) }

        // tun2socks receives packets from other interfaces; loose rp_filter is the
        // documented requirement.
        runQuiet("sysctl", "-q", "-w", "net.ipv4.conf.all.rp_filter=0")
        runQuiet("sysctl", "-q", "-w", "net.ipv4.conf.$name.rp_filter=0")

        startPump(binary, name, primaryInterface, socksPort)
        log("transparent mode: $name up, $TUN_ADDRESS, default via the tunnel (physical: $primaryInterface)")
    }

    // ------------------------------------------------------------------- macos

    private fun startMac(binary: File, primaryInterface: String, mtu: Int, socksPort: Int) {
        // macOS assigns utun devices, so a free name has to be picked up front and
        // tun2socks has to create the device before it can be configured.
        val name = freeUtun()
        device = name
        startPump(binary, name, primaryInterface, socksPort)
        waitForDevice(name)

        run("ifconfig", name, "198.18.0.1", "198.18.0.1", "up")
        runQuiet("ifconfig", name, "mtu", mtu.toString())
        for (route in MAC_ROUTES) {
            run("route", "add", "-net", route, "198.18.0.1")
            // a for loop variable is a fresh val per iteration, so capturing it here
            // deletes exactly the route that was just added
            teardown.add { runQuiet("route", "delete", "-net", route) }
        }
        log("transparent mode: $name up, all IPv4 traffic routed into the tunnel (physical: $primaryInterface)")
    }

    // ----------------------------------------------------------------- windows

    private fun startWindows(binary: File, primaryInterface: String, mtu: Int, socksPort: Int) {
        val name = WINDOWS_DEVICE
        device = name
        startPump(binary, name, primaryInterface, socksPort)
        waitForDevice(name)

        run(
            "netsh", "interface", "ipv4", "set", "address",
            "name=$name", "source=static", "addr=198.18.0.1", "mask=255.254.0.0",
        )
        runQuiet(
            "netsh", "interface", "ipv4", "set", "dnsservers",
            "name=$name", "static", "address=8.8.8.8", "register=none", "validate=no",
        )
        run("netsh", "interface", "ipv4", "add", "route", "0.0.0.0/0", name, "198.18.0.1", "metric=1")
        teardown.add {
            runQuiet("netsh", "interface", "ipv4", "delete", "route", "0.0.0.0/0", name)
        }
        runQuiet("netsh", "interface", "ipv4", "set", "interface", "name=$name", "mtu=$mtu")
        log("transparent mode: $name up, default route via the tunnel (physical: $primaryInterface)")
    }

    // -------------------------------------------------------------------- pump

    private fun startPump(binary: File, dev: String, primaryInterface: String, socksPort: Int) {
        val deviceArg = if (DesktopRuntime.isWindows) WINDOWS_DEVICE else dev
        val command = listOf(
            binary.absolutePath,
            "--device", deviceArg,
            "--proxy", "socks5://127.0.0.1:$socksPort",
            "--interface", primaryInterface,
            "--loglevel", "info",
        )
        log("starting ${binary.name}: ${command.joinToString(" ")}")
        val process = ProcessBuilder(command)
            .directory(binary.parentFile)
            .redirectErrorStream(true)
            .start()
        pump = process
        Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { log("[tun2socks] $it") } }
        }.apply { isDaemon = true }.start()
        Thread {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            if (running && code != 0) log("tun2socks exited with code $code")
        }.apply { isDaemon = true }.start()
    }

    private fun waitForDevice(name: String) {
        val deadline = System.currentTimeMillis() + DEVICE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (pump?.isAlive != true) throw TunException("tun2socks terminated while creating the device")
            val visible = when (DesktopRuntime.os) {
                "windows" -> runQuiet("netsh", "interface", "show", "interface", "name=$name").isNotEmpty()
                else -> runQuiet("ifconfig", name).isNotEmpty()
            }
            if (visible) return
            Thread.sleep(200)
        }
        throw TunException("the TUN device $name did not appear in time")
    }

    private fun freeUtun(): String {
        val existing = runQuiet("ifconfig", "-l")
        for (index in 0 until 32) {
            val candidate = "utun$index"
            if (!existing.split(Regex("\\s+")).contains(candidate)) return candidate
        }
        return "utun32"
    }

    // ----------------------------------------------------------------- helpers

    private fun run(vararg command: String): String {
        val output = execute(command.toList())
        return output
    }

    private fun runQuiet(vararg command: String): String = runCatching { execute(command.toList()) }.getOrDefault("")

    private fun execute(command: List<String>): String {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw TunException("cannot run ${command.first()}: ${e.message}", e)
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw TunException("${command.joinToString(" ")} timed out")
        }
        val code = process.exitValue()
        if (code != 0) {
            val permission = output.contains("Permission denied", true) ||
                output.contains("Operation not permitted", true) ||
                output.contains("Access is denied", true) ||
                output.contains("requires elevation", true)
            val hint = if (permission) {
                when (DesktopRuntime.os) {
                    "windows" -> " (start Owenclave as Administrator)"
                    "darwin" -> " (start Owenclave with sudo)"
                    else -> " (start Owenclave as root, or grant CAP_NET_ADMIN)"
                }
            } else {
                ""
            }
            throw TunException(
                "transparent mode needs administrator rights$hint: " +
                    "${command.joinToString(" ")} failed with $output"
            )
        }
        return output
    }

    companion object {

        /** Address of the tunnel, matching the ranges used by the tun2socks docs. */
        const val TUN_ADDRESS = "198.18.0.1/15"

        /** Windows names the adapter itself. */
        const val WINDOWS_DEVICE = "wintun"

        /** Interface name used on Linux unless the settings override it. */
        const val DEFAULT_LINUX_DEVICE = "owenclave-tun"

        private const val DEVICE_TIMEOUT_MS = 15_000L

        /** The routes the tun2socks documentation adds on macOS. */
        private val MAC_ROUTES = listOf(
            "1.0.0.0/8",
            "2.0.0.0/7",
            "4.0.0.0/6",
            "8.0.0.0/5",
            "16.0.0.0/4",
            "32.0.0.0/3",
            "64.0.0.0/2",
            "128.0.0.0/1",
            "198.18.0.0/15",
        )

        /**
         * Interface the core's outbound is bound to (and that tun2socks uses as its
         * physical interface), so tunneled traffic never loops back into the tunnel.
         */
        fun primaryInterface(): String = when (DesktopRuntime.os) {
            "windows" -> windowsPrimary().first
            "darwin" -> macPrimary().first
            else -> linuxPrimary().first
        }

        fun defaultGateway(): String = when (DesktopRuntime.os) {
            "windows" -> windowsPrimary().second
            "darwin" -> macPrimary().second
            else -> linuxPrimary().second
        }

        private fun output(command: List<String>): String = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(15, TimeUnit.SECONDS)
            text
        }.getOrDefault("")

        private fun linuxPrimary(): Pair<String, String> {
            val text = output(listOf("ip", "route", "show", "default"))
            val line = text.lineSequence().firstOrNull { it.contains("default") }.orEmpty()
            val gateway = Regex("via\\s+(\\S+)").find(line)?.groupValues?.get(1).orEmpty()
            val interfaceName = Regex("dev\\s+(\\S+)").find(line)?.groupValues?.get(1).orEmpty()
            if (interfaceName.isEmpty()) {
                throw TunException("cannot detect the default network interface: ${text.trim()}")
            }
            return interfaceName to gateway
        }

        private fun macPrimary(): Pair<String, String> {
            val text = output(listOf("route", "-n", "get", "default"))
            val interfaceName = Regex("interface:\\s+(\\S+)").find(text)?.groupValues?.get(1).orEmpty()
            val gateway = Regex("gateway:\\s+(\\S+)").find(text)?.groupValues?.get(1).orEmpty()
            if (interfaceName.isEmpty()) {
                throw TunException("cannot detect the default network interface: ${text.trim()}")
            }
            return interfaceName to gateway
        }

        private fun windowsPrimary(): Pair<String, String> {
            // the $ signs belong to PowerShell, so they are escaped for Kotlin
            val script = "(Get-NetRoute -DestinationPrefix 0.0.0.0/0 | Sort-Object RouteMetric | " +
                "Select-Object -First 1) | ForEach-Object { \"\$(\$_.InterfaceAlias)|\$(\$_.NextHop)\" }"
            val text = output(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script))
            val parts = text.trim().lineSequence().lastOrNull { it.contains("|") }?.split("|")
            if (parts.isNullOrEmpty() || parts[0].isBlank()) {
                throw TunException("cannot detect the default network interface: ${text.trim()}")
            }
            return parts[0].trim() to parts.getOrElse(1) { "" }.trim()
        }

    }

}
