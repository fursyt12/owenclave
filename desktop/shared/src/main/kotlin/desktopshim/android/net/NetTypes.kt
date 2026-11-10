package android.net

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executor

class Uri private constructor(private val raw: String) {
    val scheme: String?
        get() = raw.substringBefore(":", "").takeIf { it.isNotEmpty() && raw.contains(":") }

    val authority: String?
        get() {
            val afterScheme = raw.substringAfter("://", "")
            if (afterScheme.isEmpty()) return null
            return afterScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        }

    val host: String?
        get() {
            val auth = authority ?: return null
            val hostPort = auth.substringAfterLast("@")
            return if (hostPort.startsWith("[")) {
                hostPort.substringBefore("]") + "]"
            } else {
                hostPort.substringBefore(":")
            }
        }

    val port: Int
        get() {
            val auth = authority ?: return -1
            val hostPort = auth.substringAfterLast("@")
            val portStr = if (hostPort.startsWith("[")) {
                hostPort.substringAfter("]:", "")
            } else {
                hostPort.substringAfter(":", "")
            }
            return portStr.toIntOrNull() ?: -1
        }

    val path: String?
        get() {
            val afterScheme = raw.substringAfter("://", "")
            if (afterScheme.isEmpty()) {
                val p = raw.substringAfter(":", "").substringBefore("?").substringBefore("#")
                return p.ifEmpty { null }
            }
            val slashIdx = afterScheme.indexOf('/')
            if (slashIdx < 0) return ""
            return afterScheme.substring(slashIdx).substringBefore("?").substringBefore("#")
        }

    val query: String?
        get() {
            if (!raw.contains("?")) return null
            return raw.substringAfter("?").substringBefore("#")
        }

    val fragment: String?
        get() {
            if (!raw.contains("#")) return null
            return URLDecoder.decode(raw.substringAfter("#"), Charsets.UTF_8)
        }

    val encodedFragment: String?
        get() = if (raw.contains("#")) raw.substringAfter("#") else null

    fun getQueryParameter(key: String): String? {
        val q = query ?: return null
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)
            if (k == key) {
                val v = if (pair.contains('=')) pair.substringAfter('=') else ""
                return URLDecoder.decode(v, Charsets.UTF_8)
            }
        }
        return null
    }

    fun getBooleanQueryParameter(key: String, defaultValue: Boolean): Boolean {
        val v = getQueryParameter(key)?.lowercase() ?: return defaultValue
        return v != "false" && v != "0"
    }

    fun buildUpon(): Builder = Builder(this)

    fun toFileOrNull(): File? {
        return when (scheme) {
            "file" -> File(java.net.URI(raw))
            null -> File(raw)
            else -> File(raw.removePrefix("content://"))
        }
    }

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()

    class Builder(base: Uri? = null) {
        private var scheme: String? = base?.scheme
        private var authority: String? = base?.authority
        private var path: String? = base?.path
        private val queryParams = ArrayList<Pair<String, String>>()
        private var fragment: String? = base?.fragment

        fun scheme(s: String?): Builder { scheme = s; return this }
        fun authority(a: String?): Builder { authority = a; return this }
        fun encodedAuthority(a: String?): Builder { authority = a; return this }
        fun path(p: String?): Builder { path = p; return this }
        fun appendPath(segment: String): Builder {
            val encoded = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
            path = (path?.trimEnd('/') ?: "") + "/" + encoded
            return this
        }
        fun appendQueryParameter(key: String, value: String?): Builder {
            val k = URLEncoder.encode(key, Charsets.UTF_8)
            val v = URLEncoder.encode(value.orEmpty(), Charsets.UTF_8)
            queryParams.add(k to v)
            return this
        }
        fun fragment(f: String?): Builder { fragment = f; return this }
        fun encodedFragment(f: String?): Builder { fragment = f; return this }

        fun build(): Uri {
            val sb = StringBuilder()
            if (!scheme.isNullOrEmpty()) {
                sb.append(scheme).append("://")
            }
            if (!authority.isNullOrEmpty()) {
                sb.append(authority)
            }
            if (!path.isNullOrEmpty()) {
                if (!path!!.startsWith("/")) sb.append("/")
                sb.append(path)
            }
            if (queryParams.isNotEmpty()) {
                sb.append("?")
                sb.append(queryParams.joinToString("&") { "${it.first}=${it.second}" })
            }
            if (!fragment.isNullOrEmpty()) {
                sb.append("#").append(fragment)
            }
            return Uri(sb.toString())
        }
    }

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic
        fun fromFile(file: File): Uri = Uri(file.toURI().toString())
    }
}

class Network {
    fun getAllByName(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

class LinkAddress(val address: InetAddress, val prefixLength: Int)

class LinkProperties {
    val linkAddresses: List<LinkAddress> = emptyList()
}

class NetworkCapabilities {
    companion object {
        const val TRANSPORT_CELLULAR = 0
        const val TRANSPORT_WIFI = 1
        const val TRANSPORT_BLUETOOTH = 2
        const val TRANSPORT_ETHERNET = 3
        const val TRANSPORT_VPN = 4
        const val TRANSPORT_WIFI_AWARE = 5
        const val TRANSPORT_LOWPAN = 6
        const val TRANSPORT_USB = 8
        const val TRANSPORT_THREAD = 9
        const val TRANSPORT_SATELLITE = 10
        const val NET_CAPABILITY_INTERNET = 12
        const val NET_CAPABILITY_NOT_RESTRICTED = 13
        const val NET_CAPABILITY_VALIDATED = 16
        const val NET_CAPABILITY_CAPTIVE_PORTAL = 17
    }

    val transportInfo: Any? = null
    fun hasTransport(transportType: Int): Boolean = transportType == TRANSPORT_ETHERNET
}

class NetworkRequest {
    class Builder {
        fun addCapability(capability: Int): Builder = this
        fun removeCapability(capability: Int): Builder = this
        fun build(): NetworkRequest = NetworkRequest()
    }
}

class ConnectivityManager {
    open class NetworkCallback(val flags: Int = 0) {
        companion object {
            const val FLAG_INCLUDE_LOCATION_INFO = 1
        }
        open fun onAvailable(network: Network) {}
        open fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {}
        open fun onLost(network: Network) {}
    }

    val activeNetwork: Network? = Network()
    fun getNetworkCapabilities(network: Network?): NetworkCapabilities? = NetworkCapabilities()
    fun getLinkProperties(network: Network?): LinkProperties? = LinkProperties()
    fun getConnectionOwnerUid(protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Int = -1
    fun registerBestMatchingNetworkCallback(request: NetworkRequest, callback: NetworkCallback, handler: Handler) {}
    fun requestNetwork(request: NetworkRequest, callback: NetworkCallback, handler: Handler) {}
    fun requestNetwork(request: NetworkRequest, callback: NetworkCallback) {}
    fun registerDefaultNetworkCallback(callback: NetworkCallback, handler: Handler) {}
    fun registerDefaultNetworkCallback(callback: NetworkCallback) {}
    fun unregisterNetworkCallback(callback: NetworkCallback) {}
}

class DnsResolver(context: Context? = null, looper: Any? = null) {
    companion object {
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
        const val FLAG_NO_RETRY = 1
        private val singleton = DnsResolver()
        @JvmStatic
        fun getInstance(): DnsResolver = singleton
    }

    class DnsException(val code: Int, cause: Throwable?) : Exception(cause)

    interface Callback<T> {
        fun onAnswer(answer: T, rcode: Int)
        fun onError(error: DnsException)
    }

    fun query(
        network: Network?,
        domain: String,
        nsType: Int,
        flags: Int,
        executor: Executor,
        cancellationSignal: CancellationSignal?,
        callback: Callback<in Collection<InetAddress>>,
    ) {
        executor.execute {
            try {
                val all = InetAddress.getAllByName(domain).filter { addr ->
                    when (nsType) {
                        TYPE_A -> addr.address.size == 4
                        TYPE_AAAA -> addr.address.size == 16
                        else -> true
                    }
                }
                callback.onAnswer(all, 0)
            } catch (e: Exception) {
                callback.onError(DnsException(0, e))
            }
        }
    }

    fun query(
        network: Network?,
        domain: String,
        flags: Int,
        executor: Executor,
        cancellationSignal: CancellationSignal?,
        callback: Callback<in Collection<InetAddress>>,
    ) {
        executor.execute {
            try {
                callback.onAnswer(InetAddress.getAllByName(domain).toList(), 0)
            } catch (e: Exception) {
                callback.onError(DnsException(0, e))
            }
        }
    }

    fun rawQuery(
        network: Network?,
        query: ByteArray,
        flags: Int,
        executor: Executor,
        cancellationSignal: CancellationSignal?,
        callback: Callback<in ByteArray>,
    ) {
        executor.execute {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 4000
                    val target = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
                    socket.send(DatagramPacket(query, query.size, target, 53))
                    val buf = ByteArray(4096)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    callback.onAnswer(buf.copyOf(packet.length), 0)
                }
            } catch (e: Exception) {
                callback.onError(DnsException(0, e))
            }
        }
    }
}

open class VpnService : Service() {
    companion object {
        const val SERVICE_INTERFACE = "android.net.VpnService"
        @JvmStatic
        fun prepare(context: Context): Intent? = null
    }
    open fun protect(socket: Int): Boolean = true

    open inner class Builder {
        fun setSession(session: String): Builder = this
        fun setConfigureIntent(intent: PendingIntent): Builder = this
        fun setMtu(mtu: Int): Builder = this
        fun addAddress(address: String, prefixLength: Int): Builder = this
        fun addDnsServer(address: String): Builder = this
        fun addRoute(address: String, prefixLength: Int): Builder = this
        fun addDisallowedApplication(packageName: String): Builder = this
        fun addAllowedApplication(packageName: String): Builder = this
        fun setMetered(isMetered: Boolean): Builder = this
        fun allowBypass(): Builder = this
        fun establish(): ParcelFileDescriptor? = null
    }
}

