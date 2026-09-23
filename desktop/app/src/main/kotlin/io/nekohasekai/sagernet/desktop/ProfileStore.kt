package io.nekohasekai.sagernet.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.LogLevel
import java.io.File

/** Desktop client settings. */
class DesktopSettings(
    var socksPort: Int = 10808,
    var httpPort: Int = 10809,
    /** [ROUTE_GLOBAL] proxies everything, [ROUTE_DIRECT] connects without a proxy. */
    var routeMode: String = ROUTE_GLOBAL,
    var logLevel: Int = LogLevel.INFO,
    var selectedProfileId: String? = null,
    /** Keep local/LAN traffic outside of the tunnel, like the Android default. */
    var bypassPrivateNetworks: Boolean = true,
) {

    companion object {
        const val ROUTE_GLOBAL = "global"
        const val ROUTE_DIRECT = "direct"
    }

    /** Settings are immutable for the UI: every change copies the whole object. */
    fun copy(
        socksPort: Int = this.socksPort,
        httpPort: Int = this.httpPort,
        routeMode: String = this.routeMode,
        logLevel: Int = this.logLevel,
        selectedProfileId: String? = this.selectedProfileId,
        bypassPrivateNetworks: Boolean = this.bypassPrivateNetworks,
    ) = DesktopSettings(socksPort, httpPort, routeMode, logLevel, selectedProfileId, bypassPrivateNetworks)

}

/**
 * Profiles and settings, stored as a single JSON document in the per user data
 * directory. The Android app uses Room for this; on desktop a JSON file keeps the
 * data model inspectable and portable.
 */
class ProfileStore(private val file: File = File(DesktopRuntime.dataDir, "desktop.json")) {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    val profiles = ArrayList<Profile>()

    var settings = DesktopSettings()

    fun load() {
        if (!file.isFile) return
        runCatching {
            val root = gson.fromJson(file.readText(), JsonObject::class.java) ?: return
            profiles.clear()
            root.getAsJsonArray("profiles")?.forEach { element ->
                if (element.isJsonObject) {
                    profiles.add(Profile.fromJson(element.asJsonObject))
                }
            }
            root.getAsJsonObject("settings")?.let { json ->
                settings = DesktopSettings(
                    socksPort = json.get("socksPort")?.asInt ?: 10808,
                    httpPort = json.get("httpPort")?.asInt ?: 10809,
                    routeMode = json.get("routeMode")?.asString ?: DesktopSettings.ROUTE_GLOBAL,
                    logLevel = json.get("logLevel")?.asInt ?: LogLevel.INFO,
                    selectedProfileId = json.get("selectedProfileId")?.takeIf { !it.isJsonNull }?.asString,
                    bypassPrivateNetworks = json.get("bypassPrivateNetworks")?.asBoolean ?: true,
                )
            }
        }.onFailure {
            io.nekohasekai.sagernet.ktx.Logs.w("failed to read ${file.path}", it)
        }
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JsonObject()
            root.add("settings", JsonObject().apply {
                addProperty("socksPort", settings.socksPort)
                addProperty("httpPort", settings.httpPort)
                addProperty("routeMode", settings.routeMode)
                addProperty("logLevel", settings.logLevel)
                addProperty("bypassPrivateNetworks", settings.bypassPrivateNetworks)
                settings.selectedProfileId?.let { addProperty("selectedProfileId", it) }
            })
            root.add("profiles", JsonArray().apply {
                profiles.forEach { add(it.toJson()) }
            })
            file.writeText(gson.toJson(root))
        }.onFailure {
            io.nekohasekai.sagernet.ktx.Logs.w("failed to write ${file.path}", it)
        }
    }

}
