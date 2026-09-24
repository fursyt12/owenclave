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
import androidx.compose.material3.Checkbox
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
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
            state = rememberWindowState(size = DpSize(1180.dp, 780.dp)),
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
        println("olcrtc: ${DesktopRuntime.olcrtcBinary()?.absolutePath ?: "not found"}")
        return 0
    }
    if (args.contains("--selftest")) {
        return SelfTest.run()
    }
    if (args.contains("--selftest-naive")) {
        return SelfTest.runNaive()
    }
    if (args.contains("--selftest-protocols")) {
        return ProtocolSelfTest.run()
    }
    if (args.contains("--selftest-vless")) {
        return ProtocolSelfTest.run("vless")
    }
    val protocolIndex = args.indexOf("--selftest-protocol")
    if (protocolIndex >= 0) {
        return ProtocolSelfTest.run(args.getOrNull(protocolIndex + 1))
    }
    if (args.contains("--selftest-tun")) {
        return SelfTest.runTun(stub = false)
    }
    if (args.contains("--selftest-tun-stub")) {
        return SelfTest.runTun(stub = true)
    }
    if (args.contains("--print-settings")) {
        // Print the stored values of the settings document so the renderer (and the
        // parity verifier) can see current vs Android default side by side.
        val store = ProfileStore().apply { load() }
        print(SettingsCatalogParser.load().dump { key -> store.settings.value(key) })
        return 0
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
        println(DesktopConfigBuilder.build(Profile(bean = supported), settings, emptyList(), null).json)
        return 0
    }
    return null
}

@Composable
fun App(state: AppState) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavRail(state)
                Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    Header(state)
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    when (state.section) {
                        Section.PROFILES -> ProfilesSection(state)
                        Section.SUBSCRIPTIONS -> SubscriptionsSection(state)
                        Section.RULES -> RulesSection(state)
                        Section.SETTINGS -> SettingsSection(state)
                        Section.LOG -> LogsSection(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRail(state: AppState) {
    Column(
        modifier = Modifier
            .width(190.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text("Owenclave", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            io.nekohasekai.sagernet.BuildConfig.VERSION_NAME,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Section.entries.forEach { entry ->
            if (state.section == entry) {
                Button(
                    onClick = { state.show(entry) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) { Text(entry.label, fontSize = 13.sp) }
            } else {
                OutlinedButton(
                    onClick = { state.show(entry) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) { Text(entry.label, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            state.runtimeLabel,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(state: AppState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.section.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    state.section.hint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val connected = state.isConnected
            OutlinedButton(
                onClick = { if (connected) state.disconnect() else state.connect() },
                enabled = !state.busy && (connected || state.selectedProfile != null),
            ) { Text(if (connected) "Disconnect" else "Connect") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { state.testConnection() }, enabled = !state.busy && state.isConnected) {
                Text("Test")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (state.busy) "⏳ ${state.status}" else state.status, fontSize = 13.sp)
    }
}

// ----------------------------------------------------------------- profiles

@Composable
private fun ProfilesSection(state: AppState) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
            Text("Profiles (${state.profiles.size})", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            if (state.profiles.isEmpty()) {
                Text(
                    "No profiles yet. Add a subscription on the Subscriptions tab, or paste a share link on the right.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    Card(modifier = Modifier.fillMaxWidth().clickable { state.select(profile) }) {
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
                                state.subscriptionOf(profile)?.let { subscription ->
                                    Text(
                                        "from ${subscription.displayName}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // Every parsed bean is supported; only a really
                                // empty profile cannot be connected.
                                if (profile.bean == null && profile.customConfig == null) {
                                    Text(
                                        DesktopConfigBuilder.unsupportedReason(null),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            TextButton(onClick = { state.connect(profile) }, enabled = !state.busy) { Text("Connect") }
                            TextButton(onClick = { state.remove(profile) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ImportCard(state)
        }
    }
}

@Composable
private fun ImportCard(state: AppState) {
    var text by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("Import profiles", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("Share links, Clash/Mihomo YAML, V2Ray or sing-box JSON.", fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("naive+https://user:pass@host:443, vless://..., proxies: ...") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Button(
                    onClick = {
                        state.importText(text, "pasted input")
                        text = ""
                    },
                    enabled = text.isNotBlank() && !state.busy,
                ) { Text("Import") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        state.addCustomConfig(text, "Custom config")
                        text = ""
                    },
                    enabled = text.isNotBlank() && !state.busy,
                ) { Text("Keep as raw config") }
            }
            Spacer(Modifier.height(16.dp))
            Text("Import from a file", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("/path/to/config.json") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { state.importFile(File(path.trim())) },
                    enabled = path.isNotBlank() && !state.busy,
                ) { Text("Import") }
            }
        }
    }
}

// ------------------------------------------------------------ subscriptions

@Composable
private fun SubscriptionsSection(state: AppState) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var sendHwid by remember { mutableStateOf(state.settings.sendHwid) }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Add a subscription", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.width(240.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("https://provider.example/sub/xxxx") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sendHwid, onCheckedChange = { sendHwid = it })
                    Text("Send HWID for this subscription", fontSize = 13.sp)
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = {
                            state.addSubscription(name, url, sendHwid)
                            name = ""
                            url = ""
                        },
                        enabled = url.isNotBlank() && !state.busy,
                    ) { Text("Add and update") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { state.refreshAllSubscriptions() },
                        enabled = state.subscriptions.isNotEmpty() && !state.busy,
                    ) { Text("Update all") }
                }
                Text(
                    "Updates are fetched through the connected profile when that is enabled in Settings, " +
                        "which also gets around local filtering.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Subscriptions (${state.subscriptions.size})", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (state.subscriptions.isEmpty()) {
            Text(
                "No subscriptions yet.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(state.subscriptions, key = { it.id }) { subscription ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(subscription.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    subscription.url,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    buildString {
                                        append("${subscription.profileCount} profile(s)")
                                        if (subscription.lastUpdated > 0) {
                                            append(" · updated ${formatTime(subscription.lastUpdated)}")
                                        }
                                        if (subscription.sendHwid) append(" · HWID")
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { state.refreshSubscription(subscription) },
                                enabled = !state.busy,
                            ) { Text("Update") }
                            TextButton(onClick = { state.removeSubscription(subscription) }) { Text("Delete") }
                        }
                        subscription.lastError?.let { error ->
                            Spacer(Modifier.height(4.dp))
                            Text("last error: $error", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            Text(
                                "the Log tab keeps the full attempt history",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- rules

@Composable
private fun RulesSection(state: AppState) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(RuleTarget.PROXY) }
    var domains by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var network by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Add a rule", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Action: ", fontSize = 13.sp)
                    RuleTarget.entries.forEach { candidate ->
                        if (candidate == target) {
                            Button(onClick = { target = candidate }) { Text(candidate.label) }
                        } else {
                            OutlinedButton(onClick = { target = candidate }) { Text(candidate.label) }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text("Domains: example.com, full:api.example.com, keyword:ads, regexp:^a.*") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("IP CIDR") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.width(140.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = network,
                        onValueChange = { network = it },
                        label = { Text("Network (tcp/udp)") },
                        singleLine = true,
                        modifier = Modifier.width(180.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = protocol,
                        onValueChange = { protocol = it },
                        label = { Text("Sniffed protocol") },
                        singleLine = true,
                        modifier = Modifier.width(180.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            state.addRule(
                                RoutingRule(
                                    name = name.trim(),
                                    target = target.tag,
                                    domains = domains.trim(),
                                    ip = ip.trim(),
                                    port = port.trim(),
                                    network = network.trim(),
                                    protocol = protocol.trim(),
                                )
                            )
                            name = ""
                            domains = ""
                            ip = ""
                            port = ""
                            network = ""
                            protocol = ""
                        },
                        enabled = !state.busy,
                    ) { Text("Add rule") }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Rules are evaluated top to bottom, the first match wins, and they apply on the next connect.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Rules (${state.rules.size})", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (state.rules.isEmpty()) {
            Text(
                "No rules. Traffic goes through the selected profile, except private networks when " +
                    "\"Bypass LAN\" is on.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(state.rules, key = { it.id }) { rule ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = rule.enabled, onCheckedChange = { state.toggleRule(rule) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(
                                rule.summary,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            RuleTarget.entries.firstOrNull { it.tag == rule.target }?.label ?: rule.target,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { state.removeRule(rule) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- settings

@Composable
private fun SettingsSection(state: AppState) {
    val settings = state.settings
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Local inbounds", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortField("SOCKS port", settings.socksPort) { value ->
                state.updateSettings { it.copy(socksPort = value) }
            }
            Spacer(Modifier.width(12.dp))
            PortField("HTTP port", settings.httpPort) { value ->
                state.updateSettings { it.copy(httpPort = value) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Point the system proxy or a browser at 127.0.0.1:${settings.socksPort} (SOCKS5) " +
                "or 127.0.0.1:${settings.httpPort} (HTTP).",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Routing", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
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
            ) { Text("Direct only") }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(
                onClick = { state.updateSettings { it.copy(bypassPrivateNetworks = !it.bypassPrivateNetworks) } },
            ) { Text(if (settings.bypassPrivateNetworks) "Bypass LAN: on" else "Bypass LAN: off") }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Logging", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Level: ", fontSize = 13.sp)
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
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Transparent mode (TUN)", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.tunEnabled,
                onCheckedChange = { value -> state.updateSettings { it.copy(tunEnabled = value) } },
            )
            Text("Route all system traffic through the selected profile", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = settings.tunInterface,
                onValueChange = { value -> state.updateSettings { it.copy(tunInterface = value.trim()) } },
                label = { Text("Interface name (Linux, optional)") },
                singleLine = true,
                modifier = Modifier.width(280.dp),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = settings.tunMtu.toString(),
                onValueChange = { value ->
                    value.filter { it.isDigit() }.take(5).toIntOrNull()
                        ?.takeIf { it in 576..9000 }
                        ?.let { mtu -> state.updateSettings { it.copy(tunMtu = mtu) } }
                },
                label = { Text("MTU") },
                singleLine = true,
                modifier = Modifier.width(120.dp),
            )
        }
        Text(
            "TUN mode needs administrator rights: on Linux start the client as root (or grant " +
                "CAP_NET_ADMIN), on macOS with sudo, on Windows as Administrator. It applies when " +
                "you connect, and the routes are restored when you disconnect.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "tun2socks: " + (DesktopRuntime.tun2socksBinary()?.absolutePath ?: "not found (run ./run desktop tun2socks download)"),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Device identity (HWID)", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.sendHwid,
                onCheckedChange = { value -> state.updateSettings { it.copy(sendHwid = value) } },
            )
            Text("Send HWID to subscriptions by default", fontSize = 13.sp)
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { state.resetHwid() }) { Text("Generate a new identity") }
        }
        Text(
            "Current: ${settings.currentHwid()}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Subscriptions", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.fetchSubscriptionsThroughProxy,
                onCheckedChange = { value ->
                    state.updateSettings { it.copy(fetchSubscriptionsThroughProxy = value) }
                },
            )
            Text("Fetch through the connected profile when one is running", fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Runtime", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        InfoLine("Platform", DesktopRuntime.platformTag)
        InfoLine("Data directory", DesktopRuntime.dataDir.absolutePath)
        InfoLine("Core", DesktopRuntime.coreBinary()?.absolutePath ?: "not found")
        InfoLine("NaiveProxy", DesktopRuntime.naiveBinary()?.absolutePath ?: "not found")
        InfoLine("olcrtc", DesktopRuntime.olcrtcBinary()?.absolutePath ?: "not found")
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(140.dp))
        SelectionContainer {
            Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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

// ---------------------------------------------------------------------- log

@Composable
private fun LogsSection(state: AppState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.size - 1)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log (${state.logs.size} lines)", fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(state.logText()), null)
                    state.appendLog("log copied to the clipboard")
                },
            ) { Text("Copy") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { state.clearLogs() }) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))
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

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormatter)
