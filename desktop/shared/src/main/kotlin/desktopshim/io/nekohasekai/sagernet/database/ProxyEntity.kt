package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.anytls.AnyTLSBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http3.Http3Bean
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.olcrtc.OLCRTCBean
import io.nekohasekai.sagernet.fmt.shadowquic.ShadowQUICBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.tuic5.Tuic5Bean
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

/**
 * Desktop replacement for the Room/Parcelable backed Android `ProxyEntity`.
 *
 * Android's entity is a Room row (627 lines of annotations, `Parcelable`,
 * `Context`, `R`, `TrafficStats` and settings activities). The shared
 * `ConfigBuilder.kt` only needs the profile identity, the selected protocol
 * bean and a few derived predicates, so the shim stores the bean directly
 * instead of the Android Kryo byte-array dance. The logic of
 * [requireBean], [displayName] and [needExternal] is copied from the Android
 * source, and the `TYPE_*` constants keep their exact values so generated tags
 * and routing decisions are identical.
 *
 * Semantic differences from Android:
 *
 *  - `id`/`type` default the same way, but [putBean] is the only way to select
 *    a protocol (there is no Room row to deserialise).
 *  - [displayType] returns the same strings as Android, except that the
 *    chain/config/balancer labels are the hard coded English ones because
 *    there is no resource bundle on desktop.
 *  - Traffic counters (`tx`/`rx`), `uuid`, `error`, `status`, `ping`,
 *    `connectedTime` and the settings-activity helpers are not modelled: no
 *    shared code compiled into `:desktop:shared` reads them.
 */
class ProxyEntity(
    var id: Long = 0L,
    var groupId: Long = 0L,
    var type: Int = 0,
) {

    companion object {

        const val TYPE_SOCKS = 0
        const val TYPE_HTTP = 1
        const val TYPE_SS = 2
        const val TYPE_SSR = 3
        const val TYPE_VMESS = 4
        const val TYPE_VLESS = 5
        const val TYPE_TROJAN = 6
        const val TYPE_NAIVE = 9
        const val TYPE_HYSTERIA2 = 21
        const val TYPE_SSH = 17
        const val TYPE_WG = 18
        const val TYPE_MIERU = 19
        const val TYPE_TUIC5 = 23
        const val TYPE_JUICITY = 25
        const val TYPE_HTTP3 = 26
        const val TYPE_ANYTLS = 27
        const val TYPE_SHADOWQUIC = 28
        const val TYPE_TRUSTTUNNEL = 29
        const val TYPE_SNELL = 30
        const val TYPE_OLCRTC = 31
        const val TYPE_CHAIN = 8
        const val TYPE_BALANCER = 14
        const val TYPE_CONFIG = 13

        /** Android resolves these from `R.string`, the values match the resources. */
        const val CHAIN_NAME = "Proxy chain"
        const val CONFIG_NAME = "Custom config"
        const val BALANCER_NAME = "Balancer"

    }

    var socksBean: SOCKSBean? = null
    var httpBean: HttpBean? = null
    var ssBean: ShadowsocksBean? = null
    var ssrBean: ShadowsocksRBean? = null
    var vmessBean: VMessBean? = null
    var vlessBean: VLESSBean? = null
    var trojanBean: TrojanBean? = null
    var naiveBean: NaiveBean? = null
    var hysteria2Bean: Hysteria2Bean? = null
    var mieruBean: MieruBean? = null
    var tuic5Bean: Tuic5Bean? = null
    var sshBean: SSHBean? = null
    var wgBean: WireGuardBean? = null
    var juicityBean: JuicityBean? = null
    var http3Bean: Http3Bean? = null
    var anytlsBean: AnyTLSBean? = null
    var shadowquicBean: ShadowQUICBean? = null
    var trustTunnelBean: TrustTunnelBean? = null
    var snellBean: SnellBean? = null
    var olcrtcBean: OLCRTCBean? = null
    var configBean: ConfigBean? = null
    var chainBean: ChainBean? = null
    var balancerBean: BalancerBean? = null

    /** Convenience alias for [requireBean]. */
    val bean: AbstractBean get() = requireBean()

    fun displayType() = when (type) {
        TYPE_SOCKS -> socksBean!!.protocolName()
        TYPE_HTTP -> httpBean!!.protocolName()
        TYPE_SS -> ssBean!!.protocolName()
        TYPE_SSR -> "ShadowsocksR"
        TYPE_VMESS -> "VMess"
        TYPE_VLESS -> "VLESS"
        TYPE_TROJAN -> "Trojan"
        TYPE_NAIVE -> "NaïveProxy"
        TYPE_HYSTERIA2 -> "Hysteria 2"
        TYPE_SSH -> "SSH"
        TYPE_WG -> "WireGuard"
        TYPE_MIERU -> "mieru"
        TYPE_TUIC5 -> "TUIC"
        TYPE_JUICITY -> "Juicity"
        TYPE_HTTP3 -> "HTTP/3"
        TYPE_ANYTLS -> "AnyTLS"
        TYPE_SHADOWQUIC -> "ShadowQUIC"
        TYPE_TRUSTTUNNEL -> "TrustTunnel"
        TYPE_SNELL -> snellBean!!.protocolName()
        TYPE_OLCRTC -> "olcrtc"

        TYPE_CHAIN -> CHAIN_NAME
        TYPE_CONFIG -> CONFIG_NAME
        TYPE_BALANCER -> BALANCER_NAME
        else -> "Invalid"
    }

    fun displayName() = requireBean().displayName()

    /** Mirrors `ProxyEntity.requireBean` from the Android source. */
    fun requireBean(): AbstractBean {
        return when (type) {
            TYPE_SOCKS -> socksBean
            TYPE_HTTP -> httpBean
            TYPE_SS -> ssBean
            TYPE_SSR -> ssrBean
            TYPE_VMESS -> vmessBean
            TYPE_VLESS -> vlessBean
            TYPE_TROJAN -> trojanBean
            TYPE_NAIVE -> naiveBean
            TYPE_HYSTERIA2 -> hysteria2Bean
            TYPE_SSH -> sshBean
            TYPE_WG -> wgBean
            TYPE_MIERU -> mieruBean
            TYPE_TUIC5 -> tuic5Bean
            TYPE_JUICITY -> juicityBean
            TYPE_HTTP3 -> http3Bean
            TYPE_ANYTLS -> anytlsBean
            TYPE_SHADOWQUIC -> shadowquicBean
            TYPE_TRUSTTUNNEL -> trustTunnelBean
            TYPE_SNELL -> snellBean
            TYPE_OLCRTC -> olcrtcBean

            TYPE_CONFIG -> configBean
            TYPE_CHAIN -> chainBean
            TYPE_BALANCER -> balancerBean
            else -> null
        } ?: SOCKSBean().applyDefaultValues()
    }

    /**
     * Mirrors `ProxyEntity.needExternal` from the Android source, with one
     * desktop specific difference: ShadowQUIC is **not** external here.
     *
     * On Android ([V2RayInstance]) ShadowQUIC always runs as a separate Rust
     * plugin process (`libshadowquic.so`) reached through a local SOCKS
     * listener, so `needExternal()` has to report `true` for it. The desktop Go
     * core links the ShadowQUIC engine in natively and understands the
     * `shadowquic` outbound protocol (verified with `owenclave-core test`), so
     * routing it through a plugin would needlessly require a binary the desktop
     * build does not ship. Only NaiveProxy and olcrtc remain external.
     */
    fun needExternal(): Boolean {
        return when (type) {
            TYPE_NAIVE -> true
            TYPE_OLCRTC -> true
            else -> false
        }
    }

    /** Mirrors `ProxyEntity.putBean` from the Android source. */
    fun putBean(bean: AbstractBean): ProxyEntity {
        socksBean = null
        httpBean = null
        ssBean = null
        ssrBean = null
        vmessBean = null
        vlessBean = null
        trojanBean = null
        naiveBean = null
        hysteria2Bean = null
        sshBean = null
        wgBean = null
        mieruBean = null
        tuic5Bean = null
        juicityBean = null
        http3Bean = null
        anytlsBean = null
        shadowquicBean = null
        trustTunnelBean = null
        snellBean = null
        olcrtcBean = null

        configBean = null
        chainBean = null
        balancerBean = null

        when (bean) {
            is SOCKSBean -> {
                type = TYPE_SOCKS
                socksBean = bean
            }
            is HttpBean -> {
                type = TYPE_HTTP
                httpBean = bean
            }
            is ShadowsocksBean -> {
                type = TYPE_SS
                ssBean = bean
            }
            is ShadowsocksRBean -> {
                type = TYPE_SSR
                ssrBean = bean
            }
            is VMessBean -> {
                type = TYPE_VMESS
                vmessBean = bean
            }
            is VLESSBean -> {
                type = TYPE_VLESS
                vlessBean = bean
            }
            is TrojanBean -> {
                type = TYPE_TROJAN
                trojanBean = bean
            }
            is NaiveBean -> {
                type = TYPE_NAIVE
                naiveBean = bean
            }
            is Hysteria2Bean -> {
                type = TYPE_HYSTERIA2
                hysteria2Bean = bean
            }
            is SSHBean -> {
                type = TYPE_SSH
                sshBean = bean
            }
            is WireGuardBean -> {
                type = TYPE_WG
                wgBean = bean
            }
            is MieruBean -> {
                type = TYPE_MIERU
                mieruBean = bean
            }
            is Tuic5Bean -> {
                type = TYPE_TUIC5
                tuic5Bean = bean
            }
            is JuicityBean -> {
                type = TYPE_JUICITY
                juicityBean = bean
            }
            is Http3Bean -> {
                type = TYPE_HTTP3
                http3Bean = bean
            }
            is AnyTLSBean -> {
                type = TYPE_ANYTLS
                anytlsBean = bean
            }
            is ShadowQUICBean -> {
                type = TYPE_SHADOWQUIC
                shadowquicBean = bean
            }
            is TrustTunnelBean -> {
                type = TYPE_TRUSTTUNNEL
                trustTunnelBean = bean
            }
            is SnellBean -> {
                type = TYPE_SNELL
                snellBean = bean
            }
            is OLCRTCBean -> {
                type = TYPE_OLCRTC
                olcrtcBean = bean
            }

            is ConfigBean -> {
                type = TYPE_CONFIG
                configBean = bean
            }
            is ChainBean -> {
                type = TYPE_CHAIN
                chainBean = bean
            }
            is BalancerBean -> {
                type = TYPE_BALANCER
                balancerBean = bean
            }
            else -> error("Undefined type $type")
        }
        return this
    }

    /**
     * Desktop replacement for `ProxyEntity.Dao`.
     *
     * The Android DAO is a Room interface; here each method has an empty default
     * so [SagerDatabase] can hand out a working instance with no database. The
     * desktop client can supply profiles by installing its own subclass on
     * `SagerDatabase.proxyDao` (see that file).
     */
    open class Dao {

        open fun getEntities(proxyIds: List<Long>): List<ProxyEntity> = emptyList()

        open fun getById(proxyId: Long): ProxyEntity? = null

        open fun getByGroup(groupId: Long): List<ProxyEntity> = emptyList()

    }

}
