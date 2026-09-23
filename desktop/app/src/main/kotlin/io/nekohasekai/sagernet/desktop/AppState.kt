package io.nekohasekai.sagernet.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state and operations of the desktop client.
 *
 * All blocking work (process handling, HTTP) runs on [Dispatchers.IO]; the
 * observable state is only touched from the main dispatcher.
 */
class AppState(private val scope: CoroutineScope) {

    private val store = ProfileStore().apply { load() }

    val profiles = mutableStateListOf<Profile>().apply { addAll(store.profiles) }

    var settings by mutableStateOf(store.settings)
        private set

    var selectedProfileId by mutableStateOf(store.settings.selectedProfileId)
        private set

    var connectedProfileId by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf("Ready")
        private set

    val logs = mutableStateListOf<String>()

    val runtimeLabel: String
        get() = buildString {
            append(DesktopRuntime.platformTag)
            if (DesktopRuntime.coreBinary() == null) append(" | core missing")
            if (DesktopRuntime.naiveBinary() == null) append(" | naive plugin missing")
        }

    private val runner = CoreRunner { line -> appendLog(line) }

    init {
        Logs.sink = { line -> appendLog("owenclave: $line") }
        appendLog("Owenclave desktop ${io.nekohasekai.sagernet.BuildConfig.VERSION_NAME} (${DesktopRuntime.platformTag})")
        appendLog("data directory: ${DesktopRuntime.dataDir.absolutePath}")
        appendLog("core binary: ${DesktopRuntime.coreBinary()?.absolutePath ?: "not found"}")
        appendLog("naive binary: ${DesktopRuntime.naiveBinary()?.absolutePath ?: "not found"}")
    }

    val selectedProfile: Profile?
        get() = profiles.firstOrNull { it.id == selectedProfileId }

    val isConnected: Boolean get() = connectedProfileId != null

    fun select(profile: Profile) {
        selectedProfileId = profile.id
        persist()
    }

    fun updateSettings(transform: (DesktopSettings) -> DesktopSettings) {
        settings = transform(settings)
        persist()
    }

    fun importText(text: String, source: String) {
        if (text.isBlank()) {
            status = "Nothing to import"
            return
        }
        scope.launch {
            busy = true
            status = "Importing $source ..."
            val beans = withContext(Dispatchers.IO) { runCatching { SubscriptionImporter.parse(text) } }
            beans.onSuccess { parsed ->
                if (parsed.isEmpty()) {
                    if (text.trimStart().startsWith("{")) {
                        profiles.add(Profile(name = source, customConfig = text))
                        status = "Imported raw config as a custom profile"
                    } else {
                        status = "Nothing recognised in $source"
                    }
                } else {
                    parsed.forEach { beans -> profiles.add(Profile(name = beans.name, bean = beans)) }
                    status = "Imported ${parsed.size} profile(s) from $source"
                    if (selectedProfileId == null) selectedProfileId = profiles.firstOrNull()?.id
                }
                persist()
            }.onFailure {
                status = "Import failed: ${it.message}"
                appendLog("import failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            busy = false
        }
    }

    fun importUrl(url: String) {
        if (url.isBlank()) return
        scope.launch {
            busy = true
            status = "Downloading $url ..."
            val body = withContext(Dispatchers.IO) { runCatching { SubscriptionImporter.download(url) } }
            body.onSuccess { importText(it, url) }
                .onFailure {
                    status = "Download failed: ${it.message}"
                    appendLog("download failed: ${it.javaClass.simpleName}: ${it.message}")
                    busy = false
                }
        }
    }

    fun importFile(file: java.io.File) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { file.readText() } }
            text.onSuccess { importText(it, file.name) }
                .onFailure { status = "Cannot read ${file.name}: ${it.message}" }
        }
    }

    fun addCustomConfig(text: String, name: String) {
        profiles.add(Profile(name = name.ifBlank { "Custom config" }, customConfig = text))
        selectedProfileId = profiles.lastOrNull()?.id
        persist()
        status = "Added custom config"
    }

    fun remove(profile: Profile) {
        if (connectedProfileId == profile.id) disconnect()
        profiles.remove(profile)
        if (selectedProfileId == profile.id) selectedProfileId = profiles.firstOrNull()?.id
        persist()
        status = "Removed ${profile.displayName}"
    }

    fun connect(profile: Profile? = null) {
        val target = profile ?: selectedProfile ?: return
        scope.launch {
            busy = true
            status = "Connecting to ${target.displayName} ..."
            val result = withContext(Dispatchers.IO) { runCatching { runner.start(target, settings) } }
            result.onSuccess {
                connectedProfileId = target.id
                status = "Connected to ${target.displayName} (${target.protocolName})"
            }.onFailure {
                connectedProfileId = null
                status = "Connection failed: ${it.message}"
                appendLog("connect failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            busy = false
        }
    }

    fun disconnect() {
        scope.launch {
            withContext(Dispatchers.IO) { runner.stop() }
            connectedProfileId = null
            status = "Disconnected"
        }
    }

    fun testConnection() {
        scope.launch {
            busy = true
            status = "Testing the connection ..."
            val result = withContext(Dispatchers.IO) { runCatching { runner.testConnection() } }
            result.onSuccess { status = it }
                .onFailure { status = "Test failed: ${it.message}" }
            busy = false
        }
    }

    fun clearLogs() {
        logs.clear()
    }

    fun shutdown() {
        runner.stop()
        persist()
    }

    private fun persist() {
        store.profiles.clear()
        store.profiles.addAll(profiles)
        store.settings = settings
        store.settings.selectedProfileId = selectedProfileId
        store.save()
    }

    fun appendLog(line: String) {
        synchronized(logs) {
            logs.add(line)
            while (logs.size > 800) logs.removeAt(0)
        }
    }

}
