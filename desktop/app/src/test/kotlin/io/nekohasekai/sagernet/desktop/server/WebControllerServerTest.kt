package io.nekohasekai.sagernet.desktop.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.desktop.CoreRunner
import io.nekohasekai.sagernet.desktop.DesktopConfigBuilder
import io.nekohasekai.sagernet.desktop.DesktopRuntime
import io.nekohasekai.sagernet.desktop.DesktopSettings
import io.nekohasekai.sagernet.desktop.RuleTarget
import io.nekohasekai.sagernet.desktop.SelfTest
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebControllerServerTest {

    @BeforeTest
    fun initRuntime() {
        SagerNet.application
    }

    @Test
    fun `mihomo yaml compiler resolves groups virtual direct mrs rulesets and passes core test`() {
        val yaml = """
            mixed-port: 7890
            allow-lan: true
            mode: rule
            proxies:
              - name: Direct-RU
                type: direct
              - name: DNS-OUT
                type: dns
              - name: NL-Edge
                type: hysteria2
                server: edge.example.com
                port: 443
                password: testpassword
                sni: edge.example.com
                skip-cert-verify: true
              - name: Corp-HTTPS
                type: http
                server: proxy.example.com
                port: 8443
                username: alice
                password: secret
                tls: true
                sni: proxy.example.com
            proxy-groups:
              - name: PROXY
                type: select
                proxies:
                  - NL-Edge
                  - Corp-HTTPS
                  - Direct-RU
              - name: RU-Sites
                type: select
                proxies:
                  - Direct-RU
                  - PROXY
              - name: YouTube
                type: select
                proxies:
                  - PROXY
                  - Direct-RU
            rule-providers:
              ru-inline:
                type: inline
                behavior: domain
                payload:
                  - '+.gosuslugi.ru'
                  - '*.nalog.ru'
              geosite-ru:
                type: http
                behavior: domain
                format: mrs
                url: https://github.com/MetaCubeX/meta-rules-dat/raw/meta/geo/geosite/category-ru.mrs
              geoip-for-ru:
                type: http
                behavior: ipcidr
                format: mrs
                url: https://github.com/MetaCubeX/meta-rules-dat/raw/meta/geo/geoip/ru.mrs
              telegram-ips:
                type: http
                behavior: ipcidr
                format: mrs
                url: https://github.com/MetaCubeX/meta-rules-dat/raw/meta/geo/geoip/telegram.mrs
            rules:
              - AND,((NETWORK,udp),(DST-PORT,443)),REJECT
              - RULE-SET,ru-inline,RU-Sites
              - RULE-SET,geosite-ru,RU-Sites
              - RULE-SET,geoip-for-ru,RU-Sites,no-resolve
              - RULE-SET,telegram-ips,PROXY,no-resolve
              - GEOSITE,youtube,YouTube
              - MATCH,PROXY
        """.trimIndent()

        val doc = MihomoYamlSupport.parseDocument(yaml)
        assertNotNull(doc)
        assertEquals(2, doc.profiles.size)
        assertEquals("Hysteria2", doc.profiles[0].protocolName)
        assertEquals("HTTPS", doc.profiles[1].protocolName)
        assertTrue("Direct-RU" in doc.virtualDirectNames)

        val tmpDir = Files.createTempDirectory("mihomo-rules-test").toFile()
        try {
            val groupsByName = doc.groups.associateBy { it.name }
            // Default: RU-Sites -> Direct-RU (DIRECT)
            val rulesDefault = MihomoYamlSupport.compileRules(
                rawRules = doc.rawRules,
                ruleProviders = doc.ruleProviders,
                groupsByName = groupsByName,
                groupSelections = emptyMap(),
                virtualDirect = doc.virtualDirectNames,
                virtualReject = doc.virtualRejectNames,
                cacheDir = tmpDir,
            )
            assertTrue(rulesDefault.any { it.network == "udp" && it.port == "443" && it.target == RuleTarget.BLOCK.tag })
            assertTrue(rulesDefault.any { it.domains.contains("geosite:category-ru") && it.target == RuleTarget.DIRECT.tag })
            assertTrue(rulesDefault.any { it.ip.contains("geoip:ru") && it.target == RuleTarget.DIRECT.tag })
            assertTrue(rulesDefault.any { it.ip.contains("149.154.160.0/20") && it.target == RuleTarget.PROXY.tag })

            // Switch RU-Sites -> PROXY: RU rules dynamically flip to PROXY!
            val rulesSwitched = MihomoYamlSupport.compileRules(
                rawRules = doc.rawRules,
                ruleProviders = doc.ruleProviders,
                groupsByName = groupsByName,
                groupSelections = mapOf("RU-Sites" to "PROXY"),
                virtualDirect = doc.virtualDirectNames,
                virtualReject = doc.virtualRejectNames,
                cacheDir = tmpDir,
            )
            assertTrue(rulesSwitched.any { it.domains.contains("geosite:category-ru") && it.target == RuleTarget.PROXY.tag })

            // Validate generated V2Ray config with real `owenclave-core test` when available
            val core = DesktopRuntime.coreBinary()
            if (core != null) {
                val cfg = DesktopConfigBuilder.build(
                    profile = doc.profiles.first(),
                    settings = DesktopSettings(),
                    rules = rulesDefault,
                    bindInterface = "enp7s0",
                    serverInbounds = io.nekohasekai.sagernet.desktop.ServerInboundOptions(
                        mixedPort = 17890,
                        bindAddress = "0.0.0.0",
                        tproxyPort = 17893,
                    ),
                )
                val cfgFile = File(tmpDir, "core-test.json").apply { writeText(cfg.json) }
                val pb = ProcessBuilder(core.absolutePath, "test", "-c", cfgFile.absolutePath)
                    .redirectErrorStream(true)
                DesktopRuntime.assetDir()?.let { pb.environment()["V2RAY_LOCATION_ASSET"] = it.absolutePath }
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
                assertEquals(0, proc.exitValue(), "owenclave-core rejected Mihomo-compiled config:\n$out")
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `web controller enforces secret serves ui adds http and https proxies and proxies mixed traffic`() {
        val core = DesktopRuntime.coreBinary()
        if (core == null) {
            println("[web-controller-test] skipping live core proxy check because owenclave-core binary is absent")
            return
        }

        val tmpDir = Files.createTempDirectory("owenclave-daemon-e2e").toFile()
        val targetHttp = SelfTest.LocalHttpServer("mihomo-mixed-ok")
        var upstreamProcess: Process? = null
        var engine: DaemonEngine? = null
        var web: WebControllerServer? = null

        try {
            // Start a real local HTTP proxy server using owenclave-core so we can test end-to-end forwarding!
            val upstreamPort = CoreRunner.freePort()
            val upstreamCfg = File(tmpDir, "upstream-http.json").apply {
                writeText(
                    """
                    {
                      "log": { "loglevel": "warning" },
                      "inbounds": [
                        {
                          "tag": "http-in",
                          "listen": "127.0.0.1",
                          "port": $upstreamPort,
                          "protocol": "http",
                          "settings": { "accounts": [ { "user": "u1", "pass": "p1" } ] }
                        }
                      ],
                      "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
                    }
                    """.trimIndent(),
                )
            }
            upstreamProcess = ProcessBuilder(core.absolutePath, "run", "-c", upstreamCfg.absolutePath)
                .directory(tmpDir)
                .redirectErrorStream(true)
                .start()
            SelfTest.awaitPort(upstreamPort)

            val secret = "s3cr3t-token-test"
            var mixedPort = CoreRunner.freePort()
            var lastStartErr: Exception? = null
            for (attempt in 1..3) {
                val daemonCfg = DaemonConfig(
                    configFile = null,
                    dataDir = tmpDir,
                    mixedPort = mixedPort,
                    allowLan = false,
                    bindAddress = "127.0.0.1",
                    mode = "global",
                    logLevel = "warning",
                    externalController = "127.0.0.1:0",
                    secret = secret,
                    autoUpdateMinutes = 0,
                )
                val candidate = DaemonEngine(daemonCfg)
                try {
                    candidate.start()
                    engine = candidate
                    lastStartErr = null
                    break
                } catch (e: Exception) {
                    runCatching { candidate.stop() }
                    lastStartErr = e
                    mixedPort = CoreRunner.freePort()
                }
            }
            lastStartErr?.let { throw it }
            web = WebControllerServer(engine!!).also { it.start() }
            val port = web.boundPort
            assertTrue(port > 0)

            val client = HttpClient.newHttpClient()

            // 1. /healthz and /ui/ are reachable without token
            val healthRes = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/healthz")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, healthRes.statusCode())

            val uiRes = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/ui/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, uiRes.statusCode())
            assertTrue(uiRes.body().contains("Owenclave Web Console"))
            assertTrue(uiRes.body().contains("Google Sans Flex"))

            val favRes = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/ui/favicon.svg")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, favRes.statusCode())
            assertTrue(favRes.headers().firstValue("Content-Type").orElse("").contains("image/svg+xml"))
            assertTrue(favRes.body().contains("<svg"))

            val fontRes = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/ui/fonts/google-sans-flex.ttf")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(200, fontRes.statusCode())
            assertTrue(fontRes.headers().firstValue("Content-Type").orElse("").contains("font/ttf"))
            assertTrue(fontRes.body().size > 50_000, "Expected bundled Google Sans Flex font > 50KB")

            // 2. API rejects missing or wrong token with 401
            val unauth = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/version")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauth.statusCode())

            val wrongAuth = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/version"))
                    .header("Authorization", "Bearer wrong")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, wrongAuth.statusCode())

            // 3. Add HTTPS profile (not selected) + HTTP profile (selected, pointing at local upstream)
            val addHttpsRes = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/profiles/http"))
                    .header("Authorization", "Bearer $secret")
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"tls":true,"name":"MyHTTPS","host":"example.com","port":443,"sni":"example.com","select":false}""",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(201, addHttpsRes.statusCode())
            val httpsJson = JsonParser.parseString(addHttpsRes.body()).asJsonObject
            assertEquals("HTTPS", httpsJson.get("protocol").asString)
            assertTrue(httpsJson.get("shareUri").asString.startsWith("https://"))

            val addHttpRes = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/profiles/http"))
                    .header("Authorization", "Bearer $secret")
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"tls":false,"name":"LocalHTTP","host":"127.0.0.1","port":$upstreamPort,"username":"u1","password":"p1","select":true}""",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(201, addHttpRes.statusCode())
            val httpJson = JsonParser.parseString(addHttpRes.body()).asJsonObject
            assertEquals("HTTP", httpJson.get("protocol").asString)

            // Disable private-network bypass for loopback test target by switching mode=direct? No, let's check:
            // DesktopSettings(bypassPrivateNetworks = true) sends 127.0.0.1 direct, which still hits targetHttp on 127.0.0.1,
            // AND we also verify Mihomo /proxies endpoint + /proxies/{name}/delay!
            val proxiesRes = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/proxies"))
                    .header("Authorization", "Bearer $secret")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, proxiesRes.statusCode())
            val proxiesObj = JsonParser.parseString(proxiesRes.body()).asJsonObject.getAsJsonObject("proxies")
            assertTrue(proxiesObj.has("MyHTTPS"))
            assertTrue(proxiesObj.has("LocalHTTP"))
            assertTrue(proxiesObj.has("GLOBAL"))

            // 4. Verify BOTH HTTP proxy and SOCKS5 proxy work on the SAME `mixedPort`!
            val targetUrl = "http://127.0.0.1:${targetHttp.port}/"
            for (proxyType in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
                val conn = URI.create(targetUrl).toURL().openConnection(
                    Proxy(proxyType, InetSocketAddress("127.0.0.1", mixedPort)),
                ) as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val code = conn.responseCode
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                assertEquals(200, code, "mixed-port failed for $proxyType")
                assertEquals("mihomo-mixed-ok", body.trim())
            }

            // 5. Verify Mihomo delay endpoint against local target
            val delayRes = client.send(
                HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:$port/proxies/LocalHTTP/delay?url=$targetUrl&timeout=3000"),
                )
                    .header("Authorization", "Bearer $secret")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, delayRes.statusCode())
            val delayMs = JsonParser.parseString(delayRes.body()).asJsonObject.get("delay").asInt
            assertTrue(delayMs >= 1)

            // 6. Verify /api/state exposes startedAt and observatory-derived per-profile health + delay
            val httpProfileId = httpJson.get("id").asString
            val httpsProfileId = httpsJson.get("id").asString
            engine!!.observeCoreLine(
                "2026/11/14 11:00:00 [Info] app/observatory: the outbound proxy-global-${DesktopConfigBuilder.entityIdOf(httpProfileId)} is alive:0.042",
            )
            engine.observeCoreLine(
                "2026/11/14 11:00:00 [Info] app/observatory: the outbound proxy-global-${DesktopConfigBuilder.entityIdOf(httpsProfileId)} is dead",
            )
            val stateRes = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/state"))
                    .header("Authorization", "Bearer $secret")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, stateRes.statusCode())
            val stateObj = JsonParser.parseString(stateRes.body()).asJsonObject
            assertTrue(stateObj.get("startedAt").asLong > 0L)
            val stateProfiles = stateObj.getAsJsonArray("profiles")
            val stateById = stateProfiles.associate { el ->
                val obj = el.asJsonObject
                obj.get("id").asString to obj
            }
            assertEquals("alive", stateById.getValue(httpProfileId).get("health").asString)
            assertEquals(42, stateById.getValue(httpProfileId).get("delay").asInt)
            assertEquals("dead", stateById.getValue(httpsProfileId).get("health").asString)
        } finally {
            runCatching { web?.stop() }
            runCatching { engine?.stop() }
            runCatching {
                upstreamProcess?.let { p ->
                    p.destroyForcibly()
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            targetHttp.stop()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `default owenclave UA and naive-extra clash yaml support multi-outbound routing`() {
        val tmpDir = Files.createTempDirectory("owenclave-naive-extra-test").toFile()
        try {
            val defaultCfg = DaemonConfig.load(
                args = arrayOf("--data-dir", tmpDir.absolutePath),
                env = emptyMap(),
            )
            assertTrue(
                defaultCfg.subscriptionUserAgent.matches(Regex("^Owenclave/.*$", RegexOption.IGNORE_CASE)),
                "Expected default UA to start with Owenclave/, got: ${defaultCfg.subscriptionUserAgent}",
            )

            val yaml = """
                proxies:
                  - name: Direct-RU
                    type: direct
                  - name: NL-Hy2
                    type: hysteria2
                    server: nl.example.com
                    port: 443
                    password: secret
                    sni: nl.example.com
                  - name: naive-extra-0
                    type: naive
                    server: frank.example.com
                    port: 443
                    proto: https
                    username: demo_user
                    password: demo_password
                proxy-groups:
                  - name: Blocked
                    type: select
                    proxies:
                      - Any-Available
                      - Direct-RU
                      - NL-Hy2
                  - name: YouTube
                    type: select
                    proxies:
                      - NL-Hy2
                      - naive-extra-0
                  - name: PROXY
                    type: select
                    proxies:
                      - Blocked
                      - NL-Hy2
                      - naive-extra-0
                  - name: Any-Available
                    type: fallback
                    proxies:
                      - NL-Hy2
                      - naive-extra-0
                rules:
                  - GEOSITE,youtube,YouTube
                  - DOMAIN-SUFFIX,openai.com,Blocked
                  - MATCH,PROXY
            """.trimIndent()

            val doc = MihomoYamlSupport.parseDocument(yaml)
            assertNotNull(doc)
            assertEquals(listOf("NL-Hy2", "naive-extra-0"), doc.profiles.map { it.displayName })
            val naiveProfile = doc.profiles.first { it.displayName == "naive-extra-0" }
            assertEquals("Naive", naiveProfile.protocolName)

            val profilesByName = doc.profiles.associateBy { it.displayName }
            val groupsByName = doc.groups.associateBy { it.name }
            // Switch nested group Any-Available -> naive-extra-0 while YouTube stays on NL-Hy2
            val selections = mapOf("Any-Available" to "naive-extra-0", "YouTube" to "NL-Hy2")

            val rootProfile = MihomoYamlSupport.resolveSelectedProfile("PROXY", profilesByName, groupsByName, selections)
            assertNotNull(rootProfile)
            assertEquals("naive-extra-0", rootProfile.displayName)

            val compiled = MihomoYamlSupport.compileRules(
                rawRules = doc.rawRules,
                ruleProviders = doc.ruleProviders,
                groupsByName = groupsByName,
                groupSelections = selections,
                virtualDirect = doc.virtualDirectNames,
                virtualReject = doc.virtualRejectNames,
                virtualDns = doc.virtualDnsNames,
                cacheDir = File(tmpDir, "rules"),
                profilesByName = profilesByName,
            )
            val ytRule = compiled.first { it.name.contains("GEOSITE,youtube") }
            val nlProfile = doc.profiles.first { it.displayName == "NL-Hy2" }
            assertEquals("profile:${nlProfile.id}", ytRule.target)

            // Build core config with naive-extra-0 as primary and NL-Hy2 as extra profile:
            // NaiveProxy must appear in ExternalPlugins AND core JSON must pass `owenclave-core test`!
            val built = DesktopConfigBuilder.build(
                profile = naiveProfile,
                settings = DesktopSettings(socksPort = 22080, httpPort = 29080),
                rules = compiled,
                extraProfiles = doc.profiles,
            )
            assertEquals(1, built.plugins.size, "Expected NaiveProxy external plugin to be scheduled")
            assertTrue(built.json.contains("hysteria2"), "Expected secondary Hysteria2 outbound in core JSON")

            val coreBin = DesktopRuntime.coreBinary()
            if (coreBin != null) {
                val cfgFile = File(tmpDir, "core-multi.json").apply { writeText(built.json) }
                val pb = ProcessBuilder(coreBin.absolutePath, "test", "-c", cfgFile.absolutePath)
                    .redirectErrorStream(true)
                DesktopRuntime.assetDir()?.let { pb.environment()["V2RAY_LOCATION_ASSET"] = it.absolutePath }
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                assertTrue(proc.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, proc.exitValue(), "core test failed:\n$output")
            }

            // Also verify automatic balancer + NaiveProxy + bindInterface ("enp7s0"):
            // Naive loopback SOCKS bridge must NOT get bindToDevice, while real outbounds DO,
            // and routing.balancers[0] must carry fallbackTag = "bypass".
            val balancersOut = LinkedHashMap<String, io.nekohasekai.sagernet.desktop.BalancerSpec>()
            val autoRules = MihomoYamlSupport.compileRules(
                rawRules = doc.rawRules,
                ruleProviders = doc.ruleProviders,
                groupsByName = groupsByName,
                groupSelections = emptyMap(),
                virtualDirect = doc.virtualDirectNames,
                virtualReject = doc.virtualRejectNames,
                virtualDns = doc.virtualDnsNames,
                cacheDir = File(tmpDir, "rules-auto"),
                profilesByName = profilesByName,
                balancersOut = balancersOut,
                defaultFallbackToDirect = true,
            )
            val primaryBal = MihomoYamlSupport.resolveBalancerSpec(
                targetName = "PROXY",
                profilesByName = profilesByName,
                groupsByName = groupsByName,
                groupSelections = emptyMap(),
                virtualDirect = doc.virtualDirectNames,
                virtualReject = doc.virtualRejectNames,
                defaultFallbackToDirect = true,
            )
            assertNotNull(primaryBal)
            assertEquals(2, primaryBal.profileIds.size)
            assertTrue(primaryBal.fallbackToDirect)

            val balancerBuilt = DesktopConfigBuilder.build(
                profile = nlProfile,
                settings = DesktopSettings(socksPort = 22081, httpPort = 29081),
                rules = autoRules,
                bindInterface = "enp7s0",
                extraProfiles = doc.profiles,
                balancers = balancersOut.values.toList(),
                primaryBalancer = primaryBal,
            )
            assertEquals(1, balancerBuilt.plugins.size)
            val naivePluginPort = balancerBuilt.plugins.single().port
            val rootJson = JsonParser.parseString(balancerBuilt.json).asJsonObject
            val balancersJson = rootJson.getAsJsonObject("routing").getAsJsonArray("balancers")
            // 1 balancer for `Any-Available` (deduplicated across primary + Blocked + MATCH)
            // and 1 single-node fallback balancer for `YouTube` -> `NL-Hy2`
            assertEquals(2, balancersJson.size())
            balancersJson.forEach { b ->
                assertEquals("bypass", b.asJsonObject.get("fallbackTag").asString)
            }

            val outboundsByTag = rootJson.getAsJsonArray("outbounds")
                .map { it.asJsonObject }
                .associateBy { it.get("tag").asString }
            assertEquals(
                "enp7s0",
                outboundsByTag.getValue("bypass")
                    .getAsJsonObject("streamSettings")
                    .getAsJsonObject("sockopt")
                    .get("bindToDevice").asString,
            )
            val naiveBridgeOutbound = rootJson.getAsJsonArray("outbounds")
                .map { it.asJsonObject }
                .first { ob ->
                    ob.get("protocol")?.asString == "socks" &&
                        ob.getAsJsonObject("settings")
                            ?.getAsJsonArray("servers")
                            ?.get(0)?.asJsonObject?.get("port")?.asInt == naivePluginPort
                }
            val naiveBind = naiveBridgeOutbound.getAsJsonObject("streamSettings")
                ?.getAsJsonObject("sockopt")
                ?.get("bindToDevice")
            assertEquals(null, naiveBind, "Loopback SOCKS bridge to local NaiveProxy must not set bindToDevice")

            if (coreBin != null) {
                val cfgFile = File(tmpDir, "core-balancer-naive.json").apply { writeText(balancerBuilt.json) }
                val pb = ProcessBuilder(coreBin.absolutePath, "test", "-c", cfgFile.absolutePath)
                    .redirectErrorStream(true)
                DesktopRuntime.assetDir()?.let { pb.environment()["V2RAY_LOCATION_ASSET"] = it.absolutePath }
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                assertTrue(proc.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, proc.exitValue(), "balancer core test failed:\n$output")
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `daemon auto-failover routes via alive server and falls back to bypass when all servers are down`() {
        val core = DesktopRuntime.coreBinary()
        if (core == null) {
            println("[skip] owenclave-core binary not built on host; skipping live failover test")
            return
        }

        val tmpDir = Files.createTempDirectory("owenclave-failover-e2e").toFile()
        val targetHttp = SelfTest.LocalHttpServer("failover-ok")
        var upstreamProcess: Process? = null
        var engine: DaemonEngine? = null

        try {
            val deadPort = CoreRunner.freePort()
            val upstreamPort = CoreRunner.freePort()
            val accessLog = File(tmpDir, "upstream-access.log")
            val upstreamCfg = File(tmpDir, "upstream-alive.json").apply {
                writeText(
                    """
                    {
                      "log": {
                        "loglevel": "info",
                        "access": "${accessLog.absolutePath.replace("\\", "\\\\")}"
                      },
                      "inbounds": [
                        {
                          "tag": "http-in",
                          "listen": "127.0.0.1",
                          "port": $upstreamPort,
                          "protocol": "http",
                          "settings": { "accounts": [ { "user": "u1", "pass": "p1" } ] }
                        }
                      ],
                      "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
                    }
                    """.trimIndent(),
                )
            }
            upstreamProcess = ProcessBuilder(core.absolutePath, "run", "-c", upstreamCfg.absolutePath)
                .directory(tmpDir)
                .redirectErrorStream(true)
                .start()
            SelfTest.awaitPort(upstreamPort)

            val configYaml = File(tmpDir, "config.yaml").apply {
                writeText(
                    """
                    mode: rule
                    log-level: info
                    fallback-to-direct: true
                    proxies:
                      - name: Dead-Server-1
                        type: http
                        server: 127.0.0.1
                        port: $deadPort
                      - name: Alive-Server-2
                        type: http
                        server: 127.0.0.1
                        port: $upstreamPort
                        username: u1
                        password: p1
                    proxy-groups:
                      - name: Any-Available
                        type: fallback
                        include-all: true
                        url: "http://127.0.0.1:${targetHttp.port}/"
                        interval: 1
                      - name: Blocked
                        type: select
                        proxies:
                          - Any-Available
                    rules:
                      - IP-CIDR,127.0.0.1/32,Blocked
                      - MATCH,Blocked
                    """.trimIndent(),
                )
            }

            var mixedPort = CoreRunner.freePort()
            var startErr: Exception? = null
            for (attempt in 1..3) {
                val cfg = DaemonConfig.load(
                    args = arrayOf(
                        "--config", configYaml.absolutePath,
                        "--data-dir", File(tmpDir, "data-$attempt").absolutePath,
                        "--mixed-port", mixedPort.toString(),
                        "--bind-address", "127.0.0.1",
                        "--controller", "127.0.0.1:0",
                    ),
                    env = emptyMap(),
                )
                assertTrue(cfg.fallbackToDirect)
                val candidate = DaemonEngine(cfg)
                try {
                    candidate.start()
                    engine = candidate
                    startErr = null
                    break
                } catch (e: Exception) {
                    runCatching { candidate.stop() }
                    startErr = e
                    mixedPort = CoreRunner.freePort()
                }
            }
            startErr?.let { throw it }

            // Wait for multiObservatory's initial probe to hit Alive-Server-2
            val probeDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < probeDeadline) {
                if (accessLog.isFile && accessLog.readLines().isNotEmpty()) break
                Thread.sleep(100)
            }
            assertTrue(accessLog.isFile && accessLog.readLines().isNotEmpty(), "Expected Alive-Server-2 to receive observatory probe")

            // Phase 1: Dead-Server-1 is down, Alive-Server-2 is up -> traffic MUST route via Alive-Server-2
            val linesBefore = accessLog.readLines().size
            val targetUrl = "http://127.0.0.1:${targetHttp.port}/"
            val conn1 = URI.create(targetUrl).toURL().openConnection(
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort)),
            ) as HttpURLConnection
            conn1.connectTimeout = 5000
            conn1.readTimeout = 5000
            assertEquals(200, conn1.responseCode)
            assertEquals("failover-ok", conn1.inputStream.bufferedReader().use { it.readText() }.trim())
            conn1.disconnect()
            Thread.sleep(100)
            assertTrue(
                accessLog.readLines().size > linesBefore,
                "Expected rule-matched request to route through Alive-Server-2 while it is healthy",
            )

            // Phase 2: Terminate Alive-Server-2 so ALL proxy servers are unreachable -> must fall back to bypass!
            upstreamProcess.destroyForcibly()
            upstreamProcess.waitFor(3, TimeUnit.SECONDS)
            upstreamProcess = null

            // Wait ~1.5s for interval=1s probe to detect Alive-Server-2 is dead
            Thread.sleep(1600)

            var bypassSucceeded = false
            val fallbackDeadline = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < fallbackDeadline) {
                val ok = runCatching {
                    val conn2 = URI.create(targetUrl).toURL().openConnection(
                        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort)),
                    ) as HttpURLConnection
                    conn2.connectTimeout = 3000
                    conn2.readTimeout = 3000
                    val code = conn2.responseCode
                    val body = conn2.inputStream.bufferedReader().use { it.readText() }.trim()
                    conn2.disconnect()
                    code == 200 && body == "failover-ok"
                }.getOrDefault(false)
                if (ok) {
                    bypassSucceeded = true
                    break
                }
                Thread.sleep(300)
            }
            assertTrue(bypassSucceeded, "Expected traffic to fall back to direct bypass when ALL proxy servers are down")
        } finally {
            runCatching { engine?.stop() }
            runCatching {
                upstreamProcess?.let { p ->
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                }
            }
            targetHttp.stop()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `unchanged or failed subscription refresh keeps core running and preserves active TCP session`() {
        val coreBin = DesktopRuntime.coreBinary() ?: return
        val tmpDir = Files.createTempDirectory("owenclave-sub-preserve-test").toFile()

        val echoServer = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            while (!echoServer.isClosed) {
                val client = runCatching { echoServer.accept() }.getOrNull() ?: break
                Thread {
                    client.use { sock ->
                        val reader = sock.getInputStream().bufferedReader()
                        val writer = sock.getOutputStream().bufferedWriter()
                        while (true) {
                            val line = reader.readLine() ?: break
                            writer.write("ECHO:$line\n")
                            writer.flush()
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }

        val probeHttp = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        probeHttp.createContext("/generate_204") { ex ->
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        probeHttp.start()
        val probeUrl = "http://127.0.0.1:${probeHttp.address.port}/generate_204"

        val upstreamSocksPort = CoreRunner.freePort()
        val upstreamConfig = File(tmpDir, "upstream-sub.json").apply {
            writeText(
                """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [
                    {
                      "tag": "socks-in",
                      "port": $upstreamSocksPort,
                      "listen": "127.0.0.1",
                      "protocol": "socks",
                      "settings": { "auth": "noauth", "udp": true }
                    }
                  ],
                  "outbounds": [
                    { "tag": "direct", "protocol": "freedom", "settings": {} }
                  ]
                }
                """.trimIndent(),
            )
        }
        val upstreamPb = ProcessBuilder(coreBin.absolutePath, "run", "-c", upstreamConfig.absolutePath)
            .redirectErrorStream(true)
        DesktopRuntime.assetDir()?.let { upstreamPb.environment()["V2RAY_LOCATION_ASSET"] = it.absolutePath }
        var upstreamProc: Process? = upstreamPb.start()

        val subResponseCode = java.util.concurrent.atomic.AtomicInteger(200)
        val subResponseBody = java.util.concurrent.atomic.AtomicReference(
            """
            # version: 1
            proxies:
              - name: Node-1
                type: socks5
                server: 127.0.0.1
                port: $upstreamSocksPort
            proxy-groups:
              - name: PROXY
                type: select
                proxies:
                  - Node-1
                  - DIRECT
            rules:
              - MATCH,PROXY
            """.trimIndent(),
        )
        val subServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        subServer.createContext("/sub") { ex ->
            val code = subResponseCode.get()
            val bytes = subResponseBody.get().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/yaml; charset=utf-8")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        subServer.start()
        val subUrl = "http://127.0.0.1:${subServer.address.port}/sub"

        var engine: DaemonEngine? = null
        var tunnelSocket: java.net.Socket? = null
        try {
            // Wait until upstream SOCKS is ready
            val upDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < upDeadline) {
                val ready = runCatching {
                    java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", upstreamSocksPort), 200) }
                    true
                }.getOrDefault(false)
                if (ready) break
                Thread.sleep(100)
            }

            val mixedPort = CoreRunner.freePort()
            val cfg = DaemonConfig(
                configFile = null,
                dataDir = File(tmpDir, "data"),
                mixedPort = mixedPort,
                socksPort = 0,
                httpPort = 0,
                tproxyPort = 0,
                allowLan = false,
                bindAddress = "127.0.0.1",
                externalController = "127.0.0.1:${CoreRunner.freePort()}",
                mode = "rule",
                fallbackToDirect = false,
                logLevel = "warning",
                autoUpdateMinutes = 0,
                seedSubscriptions = listOf(DaemonSubscriptionSeed(name = "MainSub", url = subUrl)),
            )
            engine = DaemonEngine(cfg).also { it.start() }

            assertEquals(1, engine.coreStartCount())
            val initialPid = engine.corePid()
            assertNotNull(initialPid)
            val initialNodeId = engine.allProfiles().single().id

            // Open a persistent HTTP CONNECT tunnel through mixedPort -> echoServer
            val sock = java.net.Socket("127.0.0.1", mixedPort).apply { soTimeout = 5_000 }
            tunnelSocket = sock
            val out = sock.getOutputStream().bufferedWriter()
            val inp = sock.getInputStream().bufferedReader()
            out.write("CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.localPort}\r\n\r\n")
            out.flush()
            val statusLine = inp.readLine().orEmpty()
            assertTrue(statusLine.contains("200"), "Expected HTTP 200 CONNECT tunnel, got: $statusLine")
            while (inp.readLine()?.isNotEmpty() == true) {
                // drain headers
            }

            out.write("ping-1\n")
            out.flush()
            assertEquals("ECHO:ping-1", inp.readLine())

            // 1. Refresh subscription when YAML has the exact same nodes/rules (only comment timestamp changed)
            subResponseBody.set(
                """
                # version: 2 (generated later, identical proxies & rules)
                proxies:
                  - name: Node-1
                    type: socks5
                    server: 127.0.0.1
                    port: $upstreamSocksPort
                proxy-groups:
                  - name: PROXY
                    type: select
                    proxies:
                      - Node-1
                      - DIRECT
                rules:
                  - MATCH,PROXY
                """.trimIndent(),
            )
            engine.refreshAllSubscriptions()
            engine.refreshSubscription("MainSub")

            assertEquals(1, engine.coreStartCount(), "Unchanged subscription must NOT restart core")
            assertEquals(initialPid, engine.corePid(), "Core PID must remain identical on unchanged subscription")
            assertEquals(initialNodeId, engine.allProfiles().single().id)

            // Active TCP session MUST still be open!
            out.write("ping-2\n")
            out.flush()
            assertEquals("ECHO:ping-2", inp.readLine(), "Open session must survive unchanged subscription refresh")

            // 2. Refresh subscription when server fails (HTTP 503, then 200 OK with HTML/0 proxies)
            subResponseCode.set(503)
            subResponseBody.set("Service Unavailable")
            engine.refreshAllSubscriptions()
            assertTrue(runCatching { engine.refreshSubscription("MainSub") }.isFailure)

            subResponseCode.set(200)
            subResponseBody.set("<html><body>Captive Portal / Maintenance</body></html>")
            engine.refreshAllSubscriptions()
            assertTrue(runCatching { engine.refreshSubscription("MainSub") }.isFailure)

            assertEquals(1, engine.coreStartCount(), "Failed/empty subscription update must NOT restart core")
            assertEquals(initialPid, engine.corePid(), "Core PID must remain identical on failed subscription update")
            assertEquals(initialNodeId, engine.allProfiles().single().id, "Existing profiles must be preserved")
            assertNotNull(engine.subscriptionsList().single().lastError)

            // Active TCP session MUST STILL be open!
            out.write("ping-3\n")
            out.flush()
            assertEquals("ECHO:ping-3", inp.readLine(), "Open session must survive failed subscription refresh")

            // 3. Idempotent group selection / config patch with unchanged values must NOT restart core either
            assertTrue(engine.selectInGroup("PROXY", "Node-1"))
            engine.patchConfig(JsonObject().apply {
                addProperty("mode", "rule")
                addProperty("log-level", "warning")
            })
            assertEquals(1, engine.coreStartCount(), "No-op config/group update must NOT restart core")
            out.write("ping-4\n")
            out.flush()
            assertEquals("ECHO:ping-4", inp.readLine())

            // 4. Genuine subscription change (adding Node-2) SHOULD reload core once while preserving Node-1's ID
            subResponseCode.set(200)
            subResponseBody.set(
                """
                proxies:
                  - name: Node-1
                    type: socks5
                    server: 127.0.0.1
                    port: $upstreamSocksPort
                  - name: Node-2
                    type: socks5
                    server: 127.0.0.1
                    port: ${upstreamSocksPort + 1}
                proxy-groups:
                  - name: PROXY
                    type: fallback
                    url: $probeUrl
                    interval: 15
                    proxies:
                      - Node-1
                      - Node-2
                rules:
                  - MATCH,PROXY
                """.trimIndent(),
            )
            engine.refreshAllSubscriptions()
            assertEquals(2, engine.coreStartCount(), "Genuine subscription update should reload core once")
            assertEquals(listOf("Node-1", "Node-2"), engine.allProfiles().map { it.displayName })
            assertEquals(initialNodeId, engine.allProfiles().first { it.displayName == "Node-1" }.id)
        } finally {
            runCatching { tunnelSocket?.close() }
            runCatching { engine?.stop() }
            runCatching {
                upstreamProc?.let { p ->
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                }
            }
            runCatching { subServer.stop(0) }
            runCatching { probeHttp.stop(0) }
            runCatching { echoServer.close() }
            runCatching { echoThread.join(1000) }
            tmpDir.deleteRecursively()
        }
    }

}
