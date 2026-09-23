package io.nekohasekai.sagernet.desktop

import io.nekohasekai.sagernet.DesktopEnv
import java.io.File

/**
 * Locates the platform binaries (the Go core and the NaiveProxy plugin) and the
 * per user data directory.
 *
 * Lookup order:
 *  1. explicit `-Dowenclave.<name>=<path>` override
 *  2. binaries bundled inside the application jar (`/bin/<name>`, extracted to
 *     the data directory on first use)
 *  3. a development checkout (`-Dowenclave.devRoot=<repo>`), where the binaries
 *     are read straight from `desktop/<tool>/dist/<platform>`
 */
object DesktopRuntime {

    val os: String = System.getProperty("os.name", "").lowercase().let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") || it.contains("darwin") -> "darwin"
            else -> "linux"
        }
    }

    val arch: String = System.getProperty("os.arch", "").lowercase().let {
        when (it) {
            "aarch64", "arm64" -> "arm64"
            "x86", "i386", "i486", "i586", "i686" -> "386"
            else -> "amd64"
        }
    }

    val platformTag: String = "$os-$arch"

    val isWindows: Boolean = os == "windows"

    val dataDir: File get() = DesktopEnv.dataDir

    private val cache = HashMap<String, File?>()

    @Synchronized
    fun coreBinary(): File? = cache.getOrPut("core") { locate("owenclave-core") }

    @Synchronized
    fun naiveBinary(): File? = cache.getOrPut("naive") { locate("naive") }

    @Synchronized
    fun olcrtcBinary(): File? = cache.getOrPut("olcrtc") { locate("olcrtc") }

    fun executableName(baseName: String): String = if (isWindows) "$baseName.exe" else baseName

    private fun locate(baseName: String): File? {
        val fileName = executableName(baseName)
        val devRoot = System.getProperty("owenclave.devRoot")
        val candidates = ArrayList<File>()

        System.getProperty("owenclave.$baseName")?.takeIf { it.isNotBlank() }?.let { candidates.add(File(it)) }
        System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { resources ->
            candidates.add(File(resources, "bin/$fileName"))
            candidates.add(File(resources, fileName))
        }
        if (!devRoot.isNullOrBlank()) {
            candidates.add(File(devRoot, "desktop/core/dist/$platformTag/$fileName"))
            candidates.add(File(devRoot, "desktop/naive/dist/$platformTag/$fileName"))
            candidates.add(File(devRoot, "desktop/olcrtc/dist/$platformTag/$fileName"))
        }
        val workingDir = File(System.getProperty("user.dir", "."))
        candidates.add(File(workingDir, "bin/$fileName"))
        candidates.add(File(workingDir, fileName))
        candidates.add(File(workingDir, "resources/bin/$fileName"))
        candidates.add(File(workingDir, "../Resources/bin/$fileName"))
        candidates.add(File(workingDir, "../app/resources/bin/$fileName"))

        candidates.firstOrNull { it.isFile }?.let { return it }
        return extractBundled(fileName)
    }

    /** Extracts a binary that was bundled into the jar as `/bin/<fileName>`. */
    private fun extractBundled(fileName: String): File? {
        val stream = DesktopRuntime::class.java.getResourceAsStream("/bin/$fileName") ?: return null
        val targetDir = File(dataDir, "runtime").apply { mkdirs() }
        val target = File(targetDir, fileName)
        stream.use { input ->
            val temporary = File(targetDir, "$fileName.tmp")
            temporary.outputStream().use { output -> input.copyTo(output) }
            if (!target.isFile || target.length() != temporary.length()) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            } else {
                temporary.delete()
            }
        }
        target.setExecutable(true, false)
        return target
    }

}
