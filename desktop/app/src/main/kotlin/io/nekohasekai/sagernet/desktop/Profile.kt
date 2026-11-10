package io.nekohasekai.sagernet.desktop

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.anytls.AnyTLSBean
import io.nekohasekai.sagernet.fmt.gson.gson
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http3.Http3Bean
import io.nekohasekai.sagernet.fmt.masque.MASQUEBean
import io.nekohasekai.sagernet.fmt.shadowquic.ShadowQUICBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.tuic5.Tuic5Bean
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
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
    /** Set when the profile came from a subscription, see [Subscription]. */
    var subscriptionId: String? = null,
) {

    val displayName: String
        get() = name.ifBlank { bean?.displayName()?.ifBlank { null } ?: "Unnamed" }

    val protocolName: String
        get() = when (val b = bean) {
            is HttpBean -> b.protocolName()
            is SOCKSBean -> b.protocolName()
            is VLESSBean -> "VLESS"
            is VMessBean -> "VMess"
            is ShadowsocksRBean -> "ShadowsocksR"
            is Tuic5Bean -> "TUIC"
            is AnyTLSBean -> "AnyTLS"
            is SSHBean -> "SSH"
            is ShadowQUICBean -> "ShadowQUIC"
            is Http3Bean -> "HTTP3"
            is MASQUEBean -> "MASQUE"
            null -> if (customConfig != null) "Custom" else "Unknown"
            else -> b.javaClass.simpleName.removeSuffix("Bean")
        }

    val address: String
        get() = bean?.displayAddress() ?: "raw config"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        subscriptionId?.let { addProperty("subscriptionId", it) }
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
                subscriptionId = json.get("subscriptionId")?.takeIf { !it.isJsonNull }?.asString,
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
