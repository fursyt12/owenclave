import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    // The Kotlin plugin is already on the build classpath through buildSrc
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * Owenclave desktop client.
 *
 * The proxy engine is the very same Go core that the Android app links through
 * gomobile; on desktop it runs as `owenclave-core`, a child process that speaks
 * the same V2Ray-format JSON produced for Android. NaiveProxy is shipped the
 * same way as on Android as well: as an external `naive` process the core dials
 * into through a local SOCKS listener.
 */

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersion = versionProperties.getProperty("VERSION_NAME").trim()

/*
 * jpackage refuses an app-version whose first component is zero on macOS
 * ("The first number in an app-version cannot be zero or negative"), while the
 * Android app versions are 0.x. Desktop artifacts therefore map 0.a.b to 1.a.b so
 * that all three desktop platforms carry the same, valid version.
 */
val desktopVersion = appVersion.replaceFirst(Regex("^0+(?=\\.)"), "1")

/** Build host == packaging target for jpackage, so the host tuple selects the binaries. */
val hostOs = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("win") -> "windows"
        it.contains("mac") || it.contains("darwin") -> "darwin"
        else -> "linux"
    }
}
val hostArch = System.getProperty("os.arch").lowercase().let {
    when (it) {
        "aarch64", "arm64" -> "arm64"
        "x86", "i386", "i486", "i586", "i686" -> "386"
        else -> "amd64"
    }
}
val platformTag = "$hostOs-$hostArch"
val coreBinaryName = if (hostOs == "windows") "owenclave-core.exe" else "owenclave-core"
val naiveBinaryName = if (hostOs == "windows") "naive.exe" else "naive"

val coreDistDir = rootProject.layout.projectDirectory.dir("desktop/core/dist/$platformTag")
val naiveDistDir = rootProject.layout.projectDirectory.dir("desktop/naive/dist/$platformTag")

/** Directory that gets bundled into the application jar as `/bin/<binary>`. */
val runtimeResourcesDir = layout.buildDirectory.dir("desktop-resources")

val prepareDesktopRuntime = tasks.register<Sync>("prepareDesktopRuntime") {
    description = "Stages the core and NaiveProxy binaries for the current platform"
    into(runtimeResourcesDir.map { it.dir("bin") })
    from(coreDistDir) {
        include(coreBinaryName)
    }
    from(naiveDistDir) {
        include(naiveBinaryName)
    }
    doLast {
        logger.lifecycle("Staged desktop runtime for $platformTag in ${runtimeResourcesDir.get().asFile}")
    }
}

sourceSets["main"].resources.srcDir(runtimeResourcesDir)
tasks.named("processResources") { dependsOn(prepareDesktopRuntime) }

/*
 * Portable build: the jpackage application image zipped as is, so it can be
 * unpacked and started without an installer. A `data` directory next to the
 * launcher makes the app keep profiles and settings inside the unpacked folder
 * instead of the per user OS directory (`DesktopEnv` picks it up).
 */
val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app")
val portableStaging = layout.buildDirectory.dir("portable")

val preparePortable = tasks.register<Sync>("preparePortable") {
    description = "Stages the application image for the portable archive"
    dependsOn("createDistributable")
    from(appImageDir)
    into(portableStaging)
    // jpackage ships read only legal files, so a previous staging has to go away
    // before syncing again (otherwise the copy fails with "access denied").
    doFirst {
        val staging = portableStaging.get().asFile
        if (staging.exists()) staging.deleteRecursively()
    }
    doLast {
        val root = portableStaging.get().asFile.listFiles()
            ?.firstOrNull { it.isDirectory } ?: return@doLast
        File(root, "data").mkdirs()
    }
}

val packagePortable = tasks.register<Zip>("packagePortable") {
    description = "Builds the portable (unpack and run) archive for the current platform"
    dependsOn(preparePortable)
    from(portableStaging)
    archiveFileName.set("owenclave-desktop-$desktopVersion-$platformTag-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/portable"))
    // Gradle does not keep unix permissions in archives, but a portable build has
    // to keep the launcher and the bundled JRE binaries executable.
    eachFile {
        if (isDirectory || file.canExecute()) {
            permissions { unix("rwxr-xr-x") }
        } else {
            permissions { unix("rw-r--r--") }
        }
    }
    doLast {
        logger.lifecycle("Portable archive: ${archiveFile.get().asFile}")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":desktop:shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    // Clash YAML subscriptions are imported with the same parser as on Android
    implementation(libs.snakeyaml)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "io.nekohasekai.sagernet.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Owenclave"
            packageVersion = desktopVersion
            description = "Owenclave desktop proxy client"
            vendor = "owenewans"
            copyright = "GPL-3.0"

            linux {
                packageName = "owenclave"
                debMaintainer = "owenewans"
                menuGroup = "Network"
                appCategory = "Network"
            }
            macOS {
                bundleID = "org.owenewans.owenclave.desktop"
                packageName = "Owenclave"
                dockName = "Owenclave"
            }
            windows {
                menuGroup = "Owenclave"
                dirChooser = true
                upgradeUuid = "6f2f2d3c-31a5-4d3f-9a3e-8f0b6f0f0f01"
            }
        }
    }
}

/** Development runs resolve the binaries straight from the repository. */
tasks.configureEach {
    if (this is JavaExec && (name == "run" || name.startsWith("run"))) {
        dependsOn(prepareDesktopRuntime)
        systemProperty("owenclave.devRoot", rootProject.projectDir.absolutePath)
    }
    if (name.startsWith("package") || name.startsWith("createDistributable")) {
        dependsOn(prepareDesktopRuntime)
    }
}

tasks.named("build") { dependsOn(prepareDesktopRuntime) }
