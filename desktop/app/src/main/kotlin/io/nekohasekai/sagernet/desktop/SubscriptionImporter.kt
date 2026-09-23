package io.nekohasekai.sagernet.desktop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.parseClashProxies
import io.nekohasekai.sagernet.group.parseSingBoxOutbound
import io.nekohasekai.sagernet.group.parseV2Ray5Outbound
import io.nekohasekai.sagernet.group.parseV2RayOutbound
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.parseShareLinks
import org.yaml.snakeyaml.Yaml
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Subscription import for the desktop client.
 *
 * Parsing itself is the shared Android code (`ktx/Formats.kt` for share links and
 * `group/` parsers for Clash YAML / V2Ray / sing-box documents); only the
 * document level walking and the HTTP download are desktop specific, because the
 * Android `RawUpdater` is tied to Room, WorkManager and the app context.
 */
object SubscriptionImporter {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun download(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(45))
            .header("User-Agent", "Owenclave/${io.nekohasekai.sagernet.BuildConfig.VERSION_NAME}")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} while fetching the subscription" }
        return response.body()
    }

    /** Parses share links, Clash YAML, V2Ray JSON, sing-box JSON and V2Ray v5 JSON. */
    fun parse(text: String): List<AbstractBean> = parseRaw(text).map { it.applyDefaultValues() }

    private fun parseRaw(text: String): List<AbstractBean> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        runCatching { parseShareLinks(text) }
            .onFailure { Logs.d("share link parse failed: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { parseJsonDocument(trimmed) }
                .onFailure { Logs.d("json parse failed: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        runCatching { parseYamlDocument(trimmed) }
            .onFailure { Logs.d("yaml parse failed: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return emptyList()
    }

    private fun parseJsonDocument(text: String): List<AbstractBean> {
        val root = JsonParser.parseString(text)
        val beans = ArrayList<AbstractBean>()
        val objects = ArrayList<JsonObject>()
        when {
            root.isJsonArray -> root.asJsonArray.forEach { if (it.isJsonObject) objects.add(it.asJsonObject) }
            root.isJsonObject -> {
                val json = root.asJsonObject
                listOf("outbounds", "endpoints", "proxies").forEach { key ->
                    json.getAsJsonArray(key)?.forEach { if (it.isJsonObject) objects.add(it.asJsonObject) }
                }
                if (objects.isEmpty()) objects.add(json)
            }
        }
        for (outbound in objects) {
            if (outbound.has("tag") && outbound.get("tag").isJsonPrimitive &&
                outbound.get("tag").asString in listOf("direct", "block", "dns", "dns-out", "api")
            ) {
                continue
            }
            for (parser in listOf(::parseV2RayOutbound, ::parseSingBoxOutbound, ::parseV2Ray5Outbound)) {
                val parsed = runCatching { parser(outbound) }.getOrNull()
                if (!parsed.isNullOrEmpty()) {
                    beans.addAll(parsed)
                    break
                }
            }
        }
        return beans
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseYamlDocument(text: String): List<AbstractBean> {
        val document = Yaml().load<Any?>(text) as? Map<String, Any?> ?: return emptyList()
        val proxies = document["proxies"] as? List<Any?> ?: return emptyList()
        val typed = proxies.filterIsInstance<Map<String, Any?>>()
        if (typed.isEmpty()) return emptyList()
        return parseClashProxies(typed)
    }

}
