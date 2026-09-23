package libexclavecore

/**
 * Desktop replacement for the gomobile bound `libexclavecore.URL` (a wrapper
 * around Go's `net/url.URL`).
 *
 * The shared protocol code builds and parses proxy URIs with it, so the
 * implementation aims to be source compatible with the Go behaviour for the
 * subset that is actually used: scheme, userinfo, host/port, path, query
 * parameters and fragment, plus Go compatible percent encoding.
 */
class URL(var scheme: String) {

    var opaque: String = ""
    var username: String = ""
    var password: String = ""
    var host: String = ""
    var rawHost: String = ""
    var path: String = ""
    var rawPath: String = ""
        set(value) {
            field = value
            path = decode(value)
        }
    var fragment: String = ""

    private var portNumber: Int = 0
    private val query = LinkedHashMap<String, MutableList<String>>()

    var port: Int
        get() = portNumber
        set(value) {
            portNumber = value
        }

    var rawQuery: String
        get() = buildQuery()
        set(value) {
            query.clear()
            parseQuery(value)
        }

    var userInfo: String
        get() = when {
            username.isEmpty() && password.isEmpty() -> ""
            password.isEmpty() -> encodeUserInfo(username)
            else -> encodeUserInfo(username) + ":" + encodeUserInfo(password)
        }
        set(value) {
            val separator = value.indexOf(':')
            if (separator < 0) {
                username = decode(value)
                password = ""
            } else {
                username = decode(value.substring(0, separator))
                password = decode(value.substring(separator + 1))
            }
        }

    /** Fully serialised URL, equivalent to Go's `url.URL.String()`. */
    val string: String
        get() {
            val builder = StringBuilder()
            builder.append(scheme).append(':')
            if (opaque.isNotEmpty()) {
                builder.append(encodeOpaque(opaque))
            } else {
                builder.append("//")
                val info = userInfo
                if (info.isNotEmpty()) builder.append(info).append('@')
                builder.append(encodeHost(host))
                if (hasPort()) builder.append(':').append(portNumber)
                builder.append(encodePath(path))
                val q = buildQuery()
                if (q.isNotEmpty()) builder.append('?').append(q)
            }
            if (fragment.isNotEmpty()) builder.append('#').append(encodeFragment(fragment))
            return builder.toString()
        }

    fun hasUsername(): Boolean = username.isNotEmpty()

    fun hasPassword(): Boolean = password.isNotEmpty()

    fun setUsernamePassword(username: String, password: String) {
        this.username = username
        this.password = password
    }

    fun hasPort(): Boolean = portNumber != 0

    fun setHostPort(host: String, port: Int) {
        rawHost = host
        this.host = unbrace(host)
        portNumber = port
    }

    fun hasQueryParameter(key: String): Boolean = query.containsKey(key)

    fun countQueryParameters(): Long = query.size.toLong()

    fun countQueryParameter(key: String): Long = (query[key]?.size ?: 0).toLong()

    /**
     * Raw accessor, named after the gomobile generated Java method. The
     * nullable, friendlier variant lives in `ktx` as an extension function.
     */
    fun getQueryParameter(key: String): String = query[key]?.firstOrNull() ?: ""

    /** Index is a `Long` because the gomobile binding exposes the Go `int` as such. */
    fun getQueryParameterAt(key: String, index: Long): String = query[key]?.getOrNull(index.toInt()) ?: ""

    fun addQueryParameter(key: String, value: String) {
        query.getOrPut(key) { ArrayList() }.add(value)
    }

    fun setQueryParameter(key: String, value: String) {
        query[key] = arrayListOf(value)
    }

    fun deleteQueryParameter(key: String) {
        query.remove(key)
    }

    fun queryParameters(): QueryParameters = QueryParameters(query)

    override fun toString(): String = string

    private fun parseQuery(raw: String) {
        if (raw.isEmpty()) return
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            val key = if (separator < 0) decodeQuery(pair) else decodeQuery(pair.substring(0, separator))
            val value = if (separator < 0) "" else decodeQuery(pair.substring(separator + 1))
            query.getOrPut(key) { ArrayList() }.add(value)
        }
    }

    private fun buildQuery(): String {
        if (query.isEmpty()) return ""
        val builder = StringBuilder()
        for ((key, values) in query) {
            for (value in values) {
                if (builder.isNotEmpty()) builder.append('&')
                builder.append(encodeQuery(key)).append('=').append(encodeQuery(value))
            }
        }
        return builder.toString()
    }

    companion object {

        fun parse(raw: String): URL {
            val separator = raw.indexOf(':')
            require(separator > 0) { "missing protocol scheme in $raw" }
            val scheme = raw.substring(0, separator)
            require(scheme.first().isLetter()) { "invalid scheme in $raw" }

            var rest = raw.substring(separator + 1)
            val url = URL(scheme)

            if (!rest.startsWith("//")) {
                val hash = rest.indexOf('#')
                if (hash >= 0) {
                    url.fragment = decode(rest.substring(hash + 1))
                    rest = rest.substring(0, hash)
                }
                url.opaque = decode(rest)
                return url
            }

            rest = rest.substring(2)

            val hash = rest.indexOf('#')
            if (hash >= 0) {
                url.fragment = decode(rest.substring(hash + 1))
                rest = rest.substring(0, hash)
            }
            val question = rest.indexOf('?')
            if (question >= 0) {
                url.parseQuery(rest.substring(question + 1))
                rest = rest.substring(0, question)
            }
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                url.path = decode(rest.substring(slash))
                rest = rest.substring(0, slash)
            }

            // authority
            var authority = rest
            val at = authority.lastIndexOf('@')
            if (at >= 0) {
                url.userInfo = authority.substring(0, at)
                authority = authority.substring(at + 1)
            }
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                require(close > 0) { "missing ']' in host of $raw" }
                url.host = authority.substring(1, close)
                url.rawHost = url.host
                val remainder = authority.substring(close + 1)
                if (remainder.startsWith(":")) {
                    url.portNumber = requirePort(remainder.substring(1), raw)
                } else {
                    require(remainder.isEmpty()) { "invalid host $authority" }
                }
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0 && authority.indexOf(':') == colon) {
                    val candidate = authority.substring(colon + 1)
                    if (candidate.isNotEmpty() && candidate.all { it.isDigit() }) {
                        url.host = authority.substring(0, colon)
                        url.portNumber = requirePort(candidate, raw)
                    } else {
                        url.host = authority
                    }
                } else if (colon >= 0) {
                    // bare IPv6 literal without brackets
                    url.host = authority
                } else {
                    url.host = authority
                }
                url.rawHost = url.host
            }
            return url
        }

        private fun requirePort(value: String, raw: String): Int {
            val port = value.toIntOrNull()
            require(port != null && port in 0..65535) { "invalid port in $raw" }
            return port
        }
    }
}

/** Mirrors the gomobile `QueryParameters` helper. */
class QueryParameters internal constructor(private val values: Map<String, List<String>>) {

    val size: Int get() = values.keys.size

    fun keyAt(index: Int): String = values.keys.elementAt(index)

    fun valueAt(index: Int): String = values.values.elementAt(index).firstOrNull() ?: ""

}

/** Mirrors the gomobile `HostPort` struct returned by `SplitHostPort`. */
class HostPort(val host: String, val port: Int) {

    override fun toString(): String = "$host:$port"

}

internal fun unbrace(host: String): String {
    if (host.startsWith("[") && host.endsWith("]")) return host.substring(1, host.length - 1)
    return host
}

private fun encodeHost(host: String): String {
    if (host.contains(':')) return "[$host]"
    if (host.isEmpty()) return ""
    return host
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
private const val SUB_DELIMS = "!$&'()*+,;="
private const val PATH_EXTRA = ":@/"
private const val USERINFO_EXTRA = ",;:"

private fun encode(value: String, allowed: String, spacePlus: Boolean): String {
    val builder = StringBuilder()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char in UNRESERVED || char in allowed -> builder.append(char)
            char == ' ' -> builder.append(if (spacePlus) '+' else "%20")
            char == '%' && index + 2 < value.length &&
                value[index + 1].isHexDigit() && value[index + 2].isHexDigit() -> {
                builder.append(value, index, index + 3)
                index += 2
            }
            else -> {
                val bytes = char.toString().toByteArray(Charsets.UTF_8)
                for (byte in bytes) {
                    builder.append('%')
                    builder.append(HEX[(byte.toInt() shr 4) and 0xF])
                    builder.append(HEX[byte.toInt() and 0xF])
                }
            }
        }
        index++
    }
    return builder.toString()
}

/**
 * Percent decodes [value]. When [plusIsSpace] is set (query components) `+` is
 * decoded as a space, matching Go's `url.ParseQuery`; elsewhere `+` is a literal
 * character as in userinfo, path and fragment.
 */
private fun decode(value: String, plusIsSpace: Boolean = false, ): String {
    if (value.isEmpty()) return value
    val out = java.io.ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length &&
            value[index + 1].isHexDigit() && value[index + 2].isHexDigit()
        ) {
            out.write(value.substring(index + 1, index + 3).toInt(16))
            index += 3
            continue
        }
        if (char == '+' && plusIsSpace) {
            out.write(' '.code)
            index++
            continue
        }
        out.write(char.toString().toByteArray(Charsets.UTF_8))
        index++
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

private fun decodeQuery(value: String): String = decode(value, plusIsSpace = true)

private val HEX = "0123456789ABCDEF".toCharArray()

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal fun encodeQuery(value: String): String = encode(value, "", spacePlus = true)

internal fun encodePath(value: String): String = encode(value, PATH_EXTRA + SUB_DELIMS, spacePlus = false)

internal fun encodeUserInfo(value: String): String = encode(value, SUB_DELIMS + USERINFO_EXTRA, spacePlus = false)

internal fun encodeFragment(value: String): String = encode(value, PATH_EXTRA + SUB_DELIMS + "?", spacePlus = false)

internal fun encodeOpaque(value: String): String = encode(value, SUB_DELIMS + ":@/?", spacePlus = false)
