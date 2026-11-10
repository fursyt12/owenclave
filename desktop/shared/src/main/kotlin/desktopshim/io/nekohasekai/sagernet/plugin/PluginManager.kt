package io.nekohasekai.sagernet.plugin

import io.nekohasekai.sagernet.DesktopEnv
import io.nekohasekai.sagernet.SagerNet
import java.io.File

object PluginManager {
    class PluginNotFoundException(val plugin: String) : Exception("Plugin not found: $plugin")

    class InitResult(
        val path: String,
    )

    fun init(pluginId: String): InitResult? {
        if (pluginId.isBlank()) return null
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val binaryName = when (pluginId) {
            "naive-plugin" -> if (isWindows) "naive.exe" else "naive"
            else -> if (isWindows) "$pluginId.exe" else pluginId
        }
        val os = System.getProperty("os.name", "").lowercase().let {
            when {
                it.contains("win") -> "windows"
                it.contains("mac") || it.contains("darwin") -> "darwin"
                else -> "linux"
            }
        }
        val arch = System.getProperty("os.arch", "").lowercase().let {
            when (it) {
                "aarch64", "arm64" -> "arm64"
                "x86", "i386", "i486", "i586", "i686" -> "386"
                else -> "amd64"
            }
        }
        val platformTag = "$os-$arch"
        val devRoot = System.getProperty("owenclave.devRoot")
        val workingDir = File(System.getProperty("user.dir", "."))
        val resourcesDir = System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        val candidates = listOfNotNull(
            System.getProperty("owenclave.naive")?.takeIf { pluginId == "naive-plugin" && it.isNotBlank() }?.let(::File),
            System.getenv("OWENCLAVE_NAIVE_BIN")?.takeIf { pluginId == "naive-plugin" && it.isNotBlank() }?.let(::File),
            resourcesDir?.resolve("bin/$binaryName"),
            resourcesDir?.resolve(binaryName),
            devRoot?.takeIf { it.isNotBlank() }?.let { File(it, "desktop/naive/dist/$platformTag/$binaryName") },
            File(workingDir, "bin/$binaryName"),
            File(workingDir, binaryName),
            File(workingDir, "resources/bin/$binaryName"),
            File(workingDir, "../Resources/bin/$binaryName"),
            File(workingDir, "../app/resources/bin/$binaryName"),
            File(DesktopEnv.dataDir, "runtime/$binaryName"),
            File(DesktopEnv.dataDir, "bin/$binaryName"),
            DesktopEnv.dataDir.parentFile?.resolve("bin/$binaryName"),
            File(SagerNet.application.applicationInfo.nativeLibraryDir, binaryName),
        )
        val found = candidates.firstOrNull { it.isFile } ?: run {
            val stream = PluginManager::class.java.getResourceAsStream("/bin/$binaryName")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("bin/$binaryName")
            if (stream != null) {
                val target = File(DesktopEnv.dataDir, "runtime/$binaryName")
                target.parentFile?.mkdirs()
                stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                target.setExecutable(true, false)
                target
            } else {
                null
            }
        } ?: throw PluginNotFoundException(pluginId)
        return InitResult(found.absolutePath)
    }
}
