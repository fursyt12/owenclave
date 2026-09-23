package io.nekohasekai.sagernet.desktop

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.gson.gson
import java.util.UUID

/**
 * A stored proxy profile.
 *
 * Everything that is protocol specific lives in the shared [AbstractBean] of the
 * Android app, so the desktop client speaks exactly the same data model and the
 * same import parsers. A profile can alternatively carry a raw V2Ray JSON config
 * (custom profile), which is passed to the core unchanged.
 */
class Profile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var bean: AbstractBean? = null,
    var customConfig: String? = null,
) {

    val displayName: String
        get() = name.ifBlank { bean?.displayName()?.ifBlank { null } ?: "Unnamed" }

    val protocolName: String
        get() = bean?.javaClass?.simpleName?.removeSuffix("Bean")
            ?: if (customConfig != null) "Custom" else "Unknown"

    val address: String
        get() = bean?.let { "${it.serverAddress}:${it.serverPort}" } ?: "raw config"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        bean?.let {
            addProperty("beanClass", it.javaClass.name)
            add("bean", gson.toJsonTree(it))
        }
        customConfig?.let { addProperty("customConfig", it) }
    }

    companion object {

        fun fromJson(json: JsonObject): Profile {
            val profile = Profile(
                id = json.get("id")?.asString ?: UUID.randomUUID().toString(),
                name = json.get("name")?.asString.orEmpty(),
                customConfig = json.get("customConfig")?.asString,
            )
            val beanClass = json.get("beanClass")?.asString
            val beanJson = json.get("bean")
            if (!beanClass.isNullOrEmpty() && beanJson != null && !beanJson.isJsonNull) {
                runCatching {
                    val type = Class.forName(beanClass)
                    val bean = gson.fromJson(beanJson, type) as AbstractBean
                    bean.initializeDefaultValues()
                    profile.bean = bean
                }
            }
            return profile
        }

    }

}
