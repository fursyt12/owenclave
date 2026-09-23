package io.nekohasekai.sagernet.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.nekohasekai.sagernet.LogLevel
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    runCli(args)?.let { code -> exitProcess(code) }

    application {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }
        Window(
            onCloseRequest = {
                state.shutdown()
                exitApplication()
            },
            title = "Owenclave ${io.nekohasekai.sagernet.BuildConfig.VERSION_NAME} desktop",
            state = rememberWindowState(size = DpSize(1120.dp, 720.dp)),
        ) {
            App(state)
        }
    }
}

/** Headless entry points used for verification and packaging checks. */
private fun runCli(args: Array<String>): Int? {
    if (args.contains("--version")) {
        println("Owenclave desktop ${io.nekohasekai.sagernet.BuildConfig.VERSION_NAME} (${DesktopRuntime.platformTag})")
        println("data: ${DesktopRuntime.dataDir}")
        println("core: ${DesktopRuntime.coreBinary()?.absolutePath ?: "not found"}")
        println("naive: ${DesktopRuntime.naiveBinary()?.absolutePath ?: "not found"}")
        return 0
    }
    if (args.contains("--selftest")) {
        return SelfTest.run()
    }
    if (args.contains("--selftest-naive")) {
        return SelfTest.runNaive()
    }
    val printIndex = args.indexOf("--print-config")
    if (printIndex >= 0) {
        val input = args.getOrNull(printIndex + 1)
        if (input == null) {
            println("usage: --print-config <file|url|share-link>")
            return 2
        }
        val text = when {
            input.startsWith("http://") || input.startsWith("https://") -> SubscriptionImporter.download(input)
            File(input).isFile -> File(input).readText()
            else -> input
        }
        val beans = SubscriptionImporter.parse(text)
        if (beans.isEmpty()) {
            println("no profiles recognised in the input")
            return 1
        }
        println("parsed ${beans.size} profile(s):")
        beans.forEach { println("  - ${it.javaClass.simpleName}: ${it.displayName()}") }
        val supported = beans.firstOrNull { DesktopConfigBuilder.supports(it) }
        if (supported == null) {
            println("none of the parsed profiles is supported by the desktop client yet")
            return 1
        }
        val settings = DesktopSettings(
            bypassPrivateNetworks = !args.contains("--no-bypass-lan"),
            routeMode = if (args.contains("--route-direct")) DesktopSettings.ROUTE_DIRECT else DesktopSettings.ROUTE_GLOBAL,
        )
        println("--- generated core config for ${supported.displayName()} ---")
        println(DesktopConfigBuilder.build(supported, settings, null))
        return 0
    }
    return null
}

@Composable
fun App(state: AppState) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Header(state)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxSize()) {
                    ProfilesPane(state, modifier = Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
                        SettingsPane(state)
                        Spacer(Modifier.height(12.dp))
                        LogsPane(state, modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: AppState) {
    var showImport by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Owenclave desktop",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${io.nekohasekai.sagernet.BuildConfig.VERSION_NAME} · ${state.runtimeLabel}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { showImport = !showImport }) { Text(if (showImport) "Close import" else "Import") }
            Spacer(Modifier.width(8.dp))
            val connected = state.isConnected
            OutlinedButton(
                onClick = { if (connected) state.disconnect() else state.selectedProfile?.let { state.connect(it) } },
                enabled = !state.busy && (connected || state.selectedProfile != null),
            ) { Text(if (connected) "Disconnect" else "Connect") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { state.testConnection() }, enabled = !state.busy && state.isConnected) {
                Text("Test")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (state.busy) "⏳ ${state.status}" else state.status, fontSize = 13.sp)
        if (showImport) {
            Spacer(Modifier.height(12.dp))
            ImportPane(state) { showImport = false }
        }
    }
}

@Composable
private fun ImportPane(state: AppState, onDone: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Import from URL / file path", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https://example.com/subscription or /path/to/file") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val value = url.trim()
                        if (value.startsWith("http://") || value.startsWith("https://")) {
                            state.importUrl(value)
                        } else if (value.isNotEmpty()) {
                            state.importFile(File(value))
                        }
                        url = ""
                        onDone()
                    },
                    enabled = url.isNotBlank() && !state.busy,
                ) { Text("Import") }
            }
            Spacer(Modifier.height(12.dp))
            Text("Or paste share links / Clash YAML / V2Ray JSON", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("naive+https://user:pass@host:443, vless://... , proxies: ...") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Button(
                    onClick = {
                        state.importText(text, "pasted input")
                        text = ""
                        onDone()
                    },
                    enabled = text.isNotBlank() && !state.busy,
                ) { Text("Import text") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        state.addCustomConfig(text, "Custom config")
                        text = ""
                        onDone()
                    },
                    enabled = text.isNotBlank() && !state.busy,
                ) { Text("Keep as raw config") }
            }
        }
    }
}

@Composable
private fun ProfilesPane(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Profiles (${state.profiles.size})", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (state.profiles.isEmpty()) {
            Text(
                "No profiles yet. Use Import to add a subscription, a share link or a config.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.profiles, key = { it.id }) { profile ->
                val selected = profile.id == state.selectedProfileId
                val connected = profile.id == state.connectedProfileId
                val background = when {
                    connected -> MaterialTheme.colorScheme.primaryContainer
                    selected -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.select(profile) },
                ) {
                    Row(
                        modifier = Modifier.background(background).fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                (if (connected) "● " else "") + profile.displayName,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                            Text(
                                "${profile.protocolName} · ${profile.address}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (profile.bean != null && !DesktopConfigBuilder.supports(profile.bean)) {
                                Text(
                                    DesktopConfigBuilder.unsupportedReason(profile.bean),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        TextButton(onClick = { state.remove(profile) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(state: AppState) {
    val settings = state.settings
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Settings", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortField("SOCKS port", settings.socksPort) { value ->
                state.updateSettings { it.copy(socksPort = value) }
            }
            Spacer(Modifier.width(12.dp))
            PortField("HTTP port", settings.httpPort) { value ->
                state.updateSettings { it.copy(httpPort = value) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mode: ", fontSize = 13.sp)
            OutlinedButton(
                onClick = { state.updateSettings { it.copy(routeMode = DesktopSettings.ROUTE_GLOBAL) } },
                enabled = settings.routeMode != DesktopSettings.ROUTE_GLOBAL,
            ) { Text("Proxy all") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { state.updateSettings { it.copy(routeMode = DesktopSettings.ROUTE_DIRECT) } },
                enabled = settings.routeMode != DesktopSettings.ROUTE_DIRECT,
            ) { Text("Direct") }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(
                onClick = { state.updateSettings { it.copy(bypassPrivateNetworks = !it.bypassPrivateNetworks) } },
            ) { Text(if (settings.bypassPrivateNetworks) "Bypass LAN: on" else "Bypass LAN: off") }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log: ", fontSize = 13.sp)
            listOf(
                LogLevel.NONE to "none",
                LogLevel.ERROR to "error",
                LogLevel.WARNING to "warn",
                LogLevel.INFO to "info",
                LogLevel.DEBUG to "debug",
            ).forEach { (level, label) ->
                OutlinedButton(
                    onClick = { state.updateSettings { it.copy(logLevel = level) } },
                    enabled = settings.logLevel != level,
                    modifier = Modifier.padding(end = 4.dp),
                ) { Text(label, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun PortField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.takeIf { it in 1..65535 }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.width(150.dp),
    )
}

@Composable
private fun LogsPane(state: AppState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.size - 1)
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log", fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { state.clearLogs() }) { Text("Clear") }
        }
        Card(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(state.logs) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
