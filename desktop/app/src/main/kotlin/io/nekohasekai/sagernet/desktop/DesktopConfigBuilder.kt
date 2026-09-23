package io.nekohasekai.sagernet.desktop

import com.github.shadowsocks.plugin.PluginConfiguration
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import java.util.UUID

/** Raised when a profile cannot be expressed as a V2Ray config for the core. */
class UnsupportedProfileException(message: String) : Exception(message)

/** Local SOCKS listener of an external plugin process plus its credentials. */
data class PluginBinding(val port: Int, val username: String, val password: String)

/**
 * Turns a shared [AbstractBean] into the V2Ray format JSON the core consumes.
 *
 * The Android app does the same in `fmt/ConfigBuilder.kt`, but that file is bound
 * to the VPN service, Room and the Android preference store. This builder reuses
 * the identical bean model, so profiles imported on desktop (share links, Clash
 * YAML, V2Ray JSON) are interpreted exactly like on Android, and only the
 * platform specific inbounds (TUN, per app proxy) are dropped.
 */
object DesktopConfigBuilder {

    private val json = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    private val PRIVATE_NETWORKS = listOf(
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )

    /** Protocols the desktop client can build a config for. */
    fun supports(bean: AbstractBean?): Boolean = when (bean) {
        null -> false
        is NaiveBean, is ShadowsocksBean, is VMessBean, is VLESSBean, is TrojanBean,
        is SOCKSBean, is HttpBean, is Hysteria2Bean,
        -> true
        else -> false
    }

    fun unsupportedReason(bean: AbstractBean?): String = when (bean) {
        null -> "The profile is empty"
        else -> "${bean.javaClass.simpleName.removeSuffix("Bean")} is not supported by the desktop client yet"
    }

    /**
     * Builds the core configuration.
     *
     * @param plugin binding of the external plugin process, required for Naive
     *   (and eventually OLCRTC) profiles, which are reached through a local SOCKS
     *   listener exactly like on Android.
     */
    fun build(bean: AbstractBean, settings: DesktopSettings, plugin: PluginBinding?): String {
        val root = JsonObject()

        root.add("log", JsonObject().apply {
            addProperty("loglevel", logLevelName(settings.logLevel))
        })

        root.add("inbounds", JsonArray().apply {
            add(socksInbound(settings.socksPort))
            add(httpInbound(settings.httpPort))
        })

        val proxyOutbound = if (settings.routeMode == DesktopSettings.ROUTE_DIRECT) {
            freedom("proxy")
        } else {
            outboundFor(bean, plugin, settings)
        }

        root.add("outbounds", JsonArray().apply {
            add(proxyOutbound)
            add(freedom("direct"))
            add(JsonObject().apply {
                addProperty("tag", "block")
                addProperty("protocol", "blackhole")
                add("settings", JsonObject().apply {
                    add("response", JsonObject().apply { addProperty("type", "http") })
                })
            })
        })

        if (settings.routeMode != DesktopSettings.ROUTE_DIRECT && settings.bypassPrivateNetworks) {
            root.add("routing", JsonObject().apply {
                addProperty("domainStrategy", "AsIs")
                add("rules", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "field")
                        add("ip", JsonArray().apply { PRIVATE_NETWORKS.forEach { add(it) } })
                        addProperty("outboundTag", "direct")
                    })
                })
            })
        }

        return json.toJson(root)
    }

    /** Configuration for the external NaiveProxy process. */
    fun naivePluginConfig(bean: NaiveBean, port: Int, username: String, password: String): String =
        bean.buildNaiveConfig(port, username, password)

    private fun logLevelName(level: Int): String = when (level) {
        LogLevel.NONE -> "none"
        LogLevel.ERROR -> "error"
        LogLevel.WARNING -> "warning"
        LogLevel.INFO -> "info"
        else -> "debug"
    }

    private fun socksInbound(port: Int): JsonObject = JsonObject().apply {
        addProperty("tag", "socks-in")
        addProperty("listen", "127.0.0.1")
        addProperty("port", port)
        addProperty("protocol", "socks")
        add("settings", JsonObject().apply {
            addProperty("auth", "noauth")
            addProperty("udp", true)
        })
        add("sniffing", sniffing())
    }

    private fun httpInbound(port: Int): JsonObject = JsonObject().apply {
        addProperty("tag", "http-in")
        addProperty("listen", "127.0.0.1")
        addProperty("port", port)
        addProperty("protocol", "http")
        add("settings", JsonObject())
        add("sniffing", sniffing())
    }

    private fun sniffing(): JsonObject = JsonObject().apply {
        addProperty("enabled", true)
        add("destOverride", JsonArray().apply {
            add("http")
            add("tls")
            add("quic")
        })
        addProperty("routeOnly", false)
    }

    private fun freedom(tag: String): JsonObject = JsonObject().apply {
        addProperty("tag", tag)
        addProperty("protocol", "freedom")
        add("settings", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
        })
    }

    private fun outboundFor(bean: AbstractBean, plugin: PluginBinding?, settings: DesktopSettings): JsonObject =
        when (bean) {
            is NaiveBean -> naive(bean, plugin)
            is ShadowsocksBean -> shadowsocks(bean)
            is VMessBean -> vmess(bean)
            is VLESSBean -> vless(bean)
            is TrojanBean -> trojan(bean)
            is SOCKSBean -> socks(bean)
            is HttpBean -> http(bean)
            is Hysteria2Bean -> hysteria2(bean)
            else -> throw UnsupportedProfileException(unsupportedReason(bean))
        }

    /**
     * NaiveProxy runs as an external process: the core dials into its local SOCKS
     * listener, which is what the Android build does as well.
     */
    private fun naive(bean: NaiveBean, plugin: PluginBinding?): JsonObject {
        val binding = plugin
            ?: throw UnsupportedProfileException("NaiveProxy plugin is not running")
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", "127.0.0.1")
                        addProperty("port", binding.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("user", binding.username)
                                addProperty("pass", binding.password)
                            })
                        })
                    })
                })
                addProperty("version", "5")
                if (bean.singUoT == true) addProperty("uot", true)
            })
        }
    }

    private fun shadowsocks(bean: ShadowsocksBean): JsonObject = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "shadowsocks")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", bean.serverAddress)
                    addProperty("port", bean.serverPort)
                    addProperty("method", bean.method)
                    addProperty("password", bean.password)
                    if (!bean.method.startsWith("2022-blake3-") && bean.experimentReducedIvHeadEntropy == true) {
                        addProperty("experimentReducedIvHeadEntropy", true)
                    }
                })
            })
            if (bean.plugin.isNotEmpty()) {
                val configuration = PluginConfiguration(bean.plugin)
                if (configuration.selected.isNotEmpty()) {
                    addProperty("plugin", configuration.selected)
                    addProperty("pluginOpts", configuration.getOptions().toString())
                }
            }
            if (bean.singUoT == true) addProperty("uot", true)
        })
        applyStream(this, bean)
    }

    private fun vmess(bean: VMessBean): JsonObject = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "vmess")
        add("settings", JsonObject().apply {
            add("vnext", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", bean.serverAddress)
                    addProperty("port", bean.serverPort)
                    add("users", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("id", bean.uuid.orRandomUuid())
                            addProperty("security", bean.encryption.ifEmpty { "auto" })
                            (bean.alterId ?: 0).takeIf { it > 0 }?.let { addProperty("alterId", it) }
                            val experiments = ArrayList<String>()
                            if (bean.experimentalAuthenticatedLength == true) experiments.add("AuthenticatedLength")
                            if (bean.experimentalNoTerminationSignal == true) experiments.add("NoTerminationSignal")
                            if (experiments.isNotEmpty()) addProperty("experiments", experiments.joinToString("|"))
                        })
                    })
                })
            })
            bean.packetEncoding?.takeIf { it.isNotEmpty() }?.let { addProperty("packetEncoding", it) }
        })
        applyStream(this, bean)
        applyMux(this, bean)
    }

    private fun vless(bean: VLESSBean): JsonObject = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "vless")
        add("settings", JsonObject().apply {
            add("vnext", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", bean.serverAddress)
                    addProperty("port", bean.serverPort)
                    add("users", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("id", bean.uuid.orRandomUuid())
                            addProperty("encryption", bean.encryption.ifEmpty { "none" })
                            if (bean.flow.isNotEmpty()) addProperty("flow", bean.flow)
                        })
                    })
                })
            })
            bean.packetEncoding?.takeIf { it.isNotEmpty() }?.let { addProperty("packetEncoding", it) }
        })
        applyStream(this, bean)
        applyMux(this, bean)
    }

    private fun trojan(bean: TrojanBean): JsonObject = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "trojan")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", bean.serverAddress)
                    addProperty("port", bean.serverPort)
                    addProperty("password", bean.password)
                })
            })
        })
        applyStream(this, bean)
        applyMux(this, bean)
    }

    private fun socks(bean: SOCKSBean): JsonObject = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "socks")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", bean.serverAddress)
                    addProperty("port", bean.serverPort)
                    val user = bean.username
                    val pass = bean.password
                    if (!user.isNullOrEmpty() || !pass.isNullOrEmpty()) {
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("user", user ?: "")
                                addProperty("pass", pass ?: "")
                            })
                        })
                    }
                })
            })
            addProperty("version", bean.protocolVersion().toString())
        })
        applyStream(this, bean)
    }

    private fun http(bean: HttpBean): JsonObject = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "http")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", bean.serverAddress)
                    addProperty("port", bean.serverPort)
                    if (bean.username.isNotEmpty() || bean.password.isNotEmpty()) {
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("user", bean.username)
                                addProperty("pass", bean.password)
                            })
                        })
                    }
                })
            })
        })
        applyStream(this, bean)
    }

    private fun hysteria2(bean: Hysteria2Bean): JsonObject = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "hysteria2")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", bean.serverAddress)
                    addProperty("port", bean.serverPorts.firstPort() ?: bean.serverPort)
                })
            })
        })
        add("streamSettings", JsonObject().apply {
            addProperty("network", "hysteria2")
            addProperty("security", "tls")
            add("tlsSettings", hysteria2TlsSettings(bean))
            add("hy2Settings", JsonObject().apply {
                if (bean.auth.isNotEmpty()) addProperty("password", bean.auth)
                addProperty("use_udp_extension", true)
                if (bean.serverPorts.contains("-") || bean.serverPorts.contains(",")) {
                    addProperty("hopPorts", bean.serverPorts)
                    (bean.hopInterval ?: 0L).takeIf { it > 0 }?.let { addProperty("hopInterval", it) }
                }
                if (bean.obfsType.isNotEmpty() && bean.obfsPassword.isNotEmpty()) {
                    add("obfs", JsonObject().apply {
                        addProperty("type", bean.obfsType)
                        addProperty("password", bean.obfsPassword)
                        (bean.geckoMinPacketSize ?: 0).takeIf { it > 0 }?.let { addProperty("minPacketSize", it) }
                        (bean.geckoMaxPacketSize ?: 0).takeIf { it > 0 }?.let { addProperty("maxPacketSize", it) }
                    })
                }
                if ((bean.uploadMbps ?: 0L) > 0 || (bean.downloadMbps ?: 0L) > 0 || bean.congestionControl.isNotEmpty()) {
                    add("congestion", JsonObject().apply {
                        if (bean.congestionControl.isNotEmpty()) addProperty("type", bean.congestionControl)
                        (bean.uploadMbps ?: 0L).takeIf { it > 0 }?.let { addProperty("up_mbps", it) }
                        (bean.downloadMbps ?: 0L).takeIf { it > 0 }?.let { addProperty("down_mbps", it) }
                        if (bean.bbrProfile.isNotEmpty()) addProperty("bbrProfile", bean.bbrProfile)
                    })
                }
                if (bean.chromeParrot == true) addProperty("chromeParrot", true)
                if (bean.omitMaxDatagramFrameSize == true) addProperty("omitMaxDatagramFrameSize", true)
            })
        })
    }

    private fun applyStream(outbound: JsonObject, bean: StandardV2RayBean) {
        outbound.add("streamSettings", JsonObject().apply {
            val network = bean.type.ifEmpty { "tcp" }
            addProperty("network", network)
            val security = bean.security.ifEmpty { "none" }
            addProperty("security", security)

            when (security) {
                "tls", "xtls" -> add("tlsSettings", tlsSettings(bean))
                "reality" -> {
                    add("realitySettings", JsonObject().apply {
                        if (bean.sni.isNotEmpty()) addProperty("serverName", bean.sni)
                        addProperty("publicKey", bean.realityPublicKey)
                        if (bean.realityShortId.isNotEmpty()) addProperty("shortId", bean.realityShortId)
                        addProperty("fingerprint", bean.realityFingerprint.ifEmpty { bean.utlsFingerprint }.ifEmpty { "chrome" })
                        if (bean.realityMldsa65Verify.isNotEmpty()) addProperty("mldsa65Verify", bean.realityMldsa65Verify)
                        if (bean.realityDisableX25519Mlkem768 == true) addProperty("disableX25519MLKEM768", true)
                    })
                }
            }

            when (network) {
                "ws" -> add("wsSettings", JsonObject().apply {
                    addProperty("path", bean.path)
                    if (bean.host.isNotEmpty()) {
                        add("headers", JsonObject().apply { addProperty("Host", bean.host) })
                    }
                    (bean.maxEarlyData ?: 0).takeIf { it > 0 }?.let { earlyData ->
                        addProperty("maxEarlyData", earlyData)
                        if (bean.earlyDataHeaderName.isNotEmpty()) {
                            addProperty("earlyDataHeaderName", bean.earlyDataHeaderName)
                        }
                    }
                })
                "grpc" -> add("grpcSettings", JsonObject().apply {
                    if (bean.grpcServiceName.isNotEmpty()) addProperty("serviceName", bean.grpcServiceName)
                    if (bean.grpcMultiMode == true) addProperty("multiMode", true)
                    if (bean.grpcServiceNameCompat == true) addProperty("serviceNameCompat", true)
                })
                "h2", "http" -> add("httpSettings", JsonObject().apply {
                    if (bean.host.isNotEmpty()) {
                        add("host", JsonArray().apply { bean.host.listByLineOrComma().forEach { add(it) } })
                    }
                    if (bean.path.isNotEmpty()) addProperty("path", bean.path)
                })
                "httpupgrade" -> add("httpupgradeSettings", JsonObject().apply {
                    if (bean.host.isNotEmpty()) addProperty("host", bean.host)
                    addProperty("path", bean.path)
                    (bean.maxEarlyData ?: 0).takeIf { it > 0 }?.let { earlyData ->
                        addProperty("maxEarlyData", earlyData)
                        if (bean.earlyDataHeaderName.isNotEmpty()) {
                            addProperty("earlyDataHeaderName", bean.earlyDataHeaderName)
                        }
                    }
                })
                "quic" -> add("quicSettings", JsonObject().apply {
                    addProperty("security", bean.quicSecurity.ifEmpty { "none" })
                    addProperty("key", bean.quicKey)
                    add("header", JsonObject().apply { addProperty("type", bean.headerType.ifEmpty { "none" }) })
                })
                "kcp" -> add("kcpSettings", JsonObject().apply {
                    if (bean.mKcpSeed.isNotEmpty()) addProperty("seed", bean.mKcpSeed)
                    add("header", JsonObject().apply { addProperty("type", bean.headerType.ifEmpty { "none" }) })
                })
                "splithttp", "xhttp" -> add(if (network == "xhttp") "xhttpSettings" else "splithttpSettings", JsonObject().apply {
                    if (bean.host.isNotEmpty()) addProperty("host", bean.host)
                    addProperty("path", bean.path)
                    if (bean.splithttpMode.isNotEmpty()) addProperty("mode", bean.splithttpMode)
                })
                "tcp" -> if (bean.headerType == "http") {
                    add("tcpSettings", JsonObject().apply {
                        add("header", JsonObject().apply { addProperty("type", "http") })
                    })
                }
            }
        })
    }

    private fun tlsSettings(bean: StandardV2RayBean): JsonObject = JsonObject().apply {
        if (bean.sni.isNotEmpty()) addProperty("serverName", sniOf(bean))
        if (bean.allowInsecure == true) addProperty("allowInsecure", true)
        if (bean.alpn.isNotEmpty()) {
            add("alpn", JsonArray().apply { bean.alpn.listByLineOrComma().forEach { add(it) } })
        }
        if (bean.utlsFingerprint.isNotEmpty()) addProperty("fingerprint", bean.utlsFingerprint)
        if (bean.certificates.isNotEmpty()) {
            addProperty("disableSystemRoot", true)
            add("certificates", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("usage", "verify")
                    add("certificate", JsonArray().apply { bean.certificates.lines().forEach { add(it) } })
                })
            })
        }
        if (bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
            add("certificates", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("usage", "encipherment")
                    add("certificate", JsonArray().apply { bean.mtlsCertificate.lines().forEach { add(it) } })
                    add("key", JsonArray().apply { bean.mtlsCertificatePrivateKey.lines().forEach { add(it) } })
                })
            })
        }
        addPinnedCertificates(bean.pinnedPeerCertificateSha256, bean.pinnedPeerCertificatePublicKeySha256, bean.pinnedPeerCertificateChainSha256)
        if (bean.serverNameToVerify.isNotEmpty()) {
            add("serverNameToVerify", JsonArray().apply {
                bean.serverNameToVerify.listByLineOrComma().forEach { add(it) }
            })
        }
        if (bean.echEnabled == true) {
            add("ech", JsonObject().apply {
                addProperty("enabled", true)
                when {
                    bean.echConfigList.isNotEmpty() -> addProperty("config", bean.echConfigList)
                    bean.echQueryName.isNotEmpty() -> addProperty("queryDomain", bean.echQueryName)
                }
            })
        }
    }

    /** Hysteria2 has its own bean type but shares the TLS option names. */
    private fun hysteria2TlsSettings(bean: Hysteria2Bean): JsonObject = JsonObject().apply {
        if (bean.sni.isNotEmpty()) addProperty("serverName", bean.sni)
        if (bean.allowInsecure == true) addProperty("allowInsecure", true)
        if (bean.certificates.isNotEmpty()) {
            addProperty("disableSystemRoot", true)
            add("certificates", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("usage", "verify")
                    add("certificate", JsonArray().apply { bean.certificates.lines().forEach { add(it) } })
                })
            })
        }
        addPinnedCertificates(bean.pinnedPeerCertificateSha256, bean.pinnedPeerCertificatePublicKeySha256, bean.pinnedPeerCertificateChainSha256)
        if (bean.serverNameToVerify.isNotEmpty()) {
            add("serverNameToVerify", JsonArray().apply {
                bean.serverNameToVerify.listByLineOrComma().forEach { add(it) }
            })
        }
        if (bean.echEnabled == true) {
            add("ech", JsonObject().apply {
                addProperty("enabled", true)
                when {
                    bean.echConfigList.isNotEmpty() -> addProperty("config", bean.echConfigList)
                    bean.echQueryName.isNotEmpty() -> addProperty("queryDomain", bean.echQueryName)
                }
            })
        }
    }

    private fun JsonObject.addPinnedCertificates(
        sha256: String,
        publicKeySha256: String,
        chainSha256: String,
    ) {
        if (sha256.isNotEmpty()) {
            add("pinnedPeerCertificateSha256", JsonArray().apply {
                sha256.listByLineOrComma().forEach { add(it.replace(":", "")) }
            })
        }
        if (publicKeySha256.isNotEmpty()) {
            add("pinnedPeerCertificatePublicKeySha256", JsonArray().apply {
                publicKeySha256.listByLineOrComma().forEach { add(it) }
            })
        }
        if (chainSha256.isNotEmpty()) {
            add("pinnedPeerCertificateChainSha256", JsonArray().apply {
                chainSha256.listByLineOrComma().forEach { add(it) }
            })
        }
    }

    private fun sniOf(bean: StandardV2RayBean): String = bean.sni

    private fun applyMux(outbound: JsonObject, bean: StandardV2RayBean) {
        if (bean.mux == true) {
            outbound.add("mux", JsonObject().apply {
                addProperty("enabled", true)
                (bean.muxConcurrency ?: 0).takeIf { it > 0 }?.let { addProperty("concurrency", it) }
                bean.muxPacketEncoding?.takeIf { it.isNotEmpty() }?.let { addProperty("packetEncoding", it) }
            })
        }
        if (bean.singMux == true) {
            outbound.add("smux", JsonObject().apply {
                addProperty("enabled", true)
                if (bean.singMuxProtocol.isNotEmpty()) addProperty("protocol", bean.singMuxProtocol)
                (bean.singMuxMaxConnections ?: 0).takeIf { it > 0 }?.let { addProperty("maxConnections", it) }
                (bean.singMuxMinStreams ?: 0).takeIf { it > 0 }?.let { addProperty("minStreams", it) }
                (bean.singMuxMaxStreams ?: 0).takeIf { it > 0 }?.let { addProperty("maxStreams", it) }
                if (bean.singMuxPadding == true) addProperty("padding", true)
            })
        }
    }

    private fun String?.orRandomUuid(): String {
        if (this.isNullOrBlank()) return UUID.randomUUID().toString()
        return runCatching { UUID.fromString(this.trim()) }.getOrNull()?.toString() ?: UUID.randomUUID().toString()
    }

    private fun String.firstPort(): Int? =
        split(',', '-').firstNotNullOfOrNull { it.trim().toIntOrNull() }

}
