package io.nekohasekai.sagernet.desktop.server

import com.google.gson.JsonParser
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

            val mixedPort = CoreRunner.freePort()
            val secret = "s3cr3t-token-test"
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

            engine = DaemonEngine(daemonCfg).also { it.start() }
            web = WebControllerServer(engine).also { it.start() }
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
        } finally {
            runCatching { web?.stop() }
            runCatching { engine?.stop() }
            runCatching { upstreamProcess?.destroyForcibly() }
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
        } finally {
            tmpDir.deleteRecursively()
        }
    }

}
