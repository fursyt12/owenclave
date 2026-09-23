package libexclavecore

import java.net.InetAddress
import java.util.Base64

/**
 * Desktop replacement for the gomobile bound `libexclavecore` package.
 *
 * Only the helpers that are shared with the Android app are implemented; the
 * Android specific parts (TUN, VPN protection, AIDL, gomobile instance) have no
 * meaning on desktop, where the proxy core runs as a child process instead.
 */
object Libexclavecore {

    @JvmStatic
    fun newURL(scheme: String): URL = URL(scheme)

    @JvmStatic
    fun parseURL(rawURL: String): URL = URL.parse(rawURL)

    @JvmStatic
    fun isIP(input: String): Boolean = isIPv4(input) || isIPv6(input)

    @JvmStatic
    fun isIPv4(input: String): Boolean {
        val parts = input.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return false
            if (!part.all { it.isDigit() }) return false
            if (part.length > 1 && part[0] == '0') return false
            if (part.toInt() > 255) return false
        }
        return true
    }

    @JvmStatic
    fun isIPv6(input: String): Boolean {
        if (!input.contains(':')) return false
        val literal = input.substringBefore('%')
        if (literal.isEmpty()) return false
        return try {
            InetAddress.getByName(literal) is java.net.Inet6Address
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun isLoopbackIP(input: String): Boolean {
        if (!isIP(input)) return false
        return try {
            InetAddress.getByName(input.substringBefore('%')).isLoopbackAddress
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Mirrors Go's `net.SplitHostPort`: returns the host and port of a
     * `host:port` or `[v6]:port` pair. Like the gomobile binding it throws when
     * the input has no port, so callers can use the result directly.
     */
    @JvmStatic
    fun splitHostPort(str: String): HostPort {
        val trimmed = str.trim()
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            require(close >= 0) { "missing ']' in address $str" }
            val host = trimmed.substring(1, close)
            val remainder = trimmed.substring(close + 1)
            require(remainder.startsWith(":")) { "missing port in address $str" }
            val port = remainder.substring(1).toIntOrNull()
            require(port != null) { "invalid port in address $str" }
            return HostPort(host, port)
        }
        val colon = trimmed.lastIndexOf(':')
        require(colon > 0) { "missing port in address $str" }
        val port = trimmed.substring(colon + 1).toIntOrNull()
        require(port != null) { "invalid port in address $str" }
        return HostPort(trimmed.substring(0, colon), port)
    }

    @JvmStatic
    fun pemToDer(input: String): ByteArray {
        val body = input.lineSequence()
            .filterNot { it.trim().startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        return Base64.getMimeDecoder().decode(body)
    }

    @JvmStatic
    fun derToPem(input: ByteArray): String {
        val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
        return "-----BEGIN CERTIFICATE-----\n${encoder.encodeToString(input)}\n-----END CERTIFICATE-----\n"
    }

}
