package io.moduhome.app.data.transfer

import io.moduhome.app.core.transfer.RelaySenderSession
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder

/** A pairing credential, separate from proposal data. Never save this link in the inbox. */
internal object RelayLinkCodec {
    private val keys = setOf("v", "base", "room", "token", "expires")
    fun accepts(link: String): Boolean = link.startsWith("modu-home://join")

    fun encode(session: RelaySenderSession): String {
        val fields = linkedMapOf("v" to "1", "base" to session.baseUrl, "room" to session.roomId,
            "token" to session.senderToken, "expires" to session.expiresAtMillis.toString())
        return "modu-home://join?" + fields.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}"
        }
    }

    fun encodeBrowser(session: RelaySenderSession): String =
        "${session.baseUrl}/dropoff/#v=1&room=${session.roomId}&token=${session.senderToken}"

    fun decode(link: String, nowMillis: Long, allowLocalHttp: Boolean = false): RelayJoinDecodeResult {
        if (nowMillis < 0 || link.length > 8_192 || link.any { it.code !in 0x21..0x7e }) return invalid()
        return try {
            val uri = URI(link)
            if (uri.scheme != "modu-home" || uri.host != "join" || uri.rawPath.orEmpty().isNotEmpty() ||
                uri.rawUserInfo != null || uri.rawFragment != null || uri.port != -1) return invalid()
            val parts = uri.rawQuery?.split('&') ?: return invalid()
            if (parts.size != keys.size) return invalid()
            val fields = linkedMapOf<String, String>()
            for (part in parts) {
                val index = part.indexOf('=')
                if (index < 1) return invalid()
                val key = part.substring(0, index)
                if (key !in keys || key in fields) return invalid()
                fields[key] = URLDecoder.decode(part.substring(index + 1), Charsets.UTF_8.name())
            }
            if (fields["v"] != "1") return invalid()
            val base = RelayEndpoint.normalize(fields.getValue("base"), allowLocalHttp) ?: return invalid()
            val expires = fields.getValue("expires").toLongOrNull() ?: return invalid()
            if (expires <= nowMillis || expires - nowMillis > MAX_PAIRING_LIFETIME_MILLIS) {
                return RelayJoinDecodeResult.Invalid("연결 QR의 유효시간을 확인해주세요. 차량에서 다시 연결할 수 있어요")
            }
            RelayJoinDecodeResult.Valid(RelaySenderSession(base, fields.getValue("room"), fields.getValue("token"), expires))
        } catch (_: IllegalArgumentException) {
            invalid()
        } catch (_: java.net.URISyntaxException) {
            invalid()
        }
    }

    private fun invalid(): RelayJoinDecodeResult.Invalid = RelayJoinDecodeResult.Invalid("차량 연결 링크를 확인해주세요")
    const val MAX_PAIRING_LIFETIME_MILLIS = 20 * 60 * 1_000L
}

internal sealed interface RelayJoinDecodeResult {
    data class Valid(val session: RelaySenderSession) : RelayJoinDecodeResult
    data class Invalid(val reason: String) : RelayJoinDecodeResult
}

internal object RelayEndpoint {
    fun normalize(value: String, allowLocalHttp: Boolean): String? = try {
        val uri = URI(value)
        val host = uri.host?.lowercase()
        if (value.length !in 1..2_048 || value.any { it.code !in 0x21..0x7e } || host == null ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath.orEmpty() !in listOf("", "/") || (uri.port != -1 && uri.port !in 1..65_535) ||
            !(uri.scheme == "https" || (uri.scheme == "http" && allowLocalHttp && isLocalHost(host)))) null
        else uri.toASCIIString().trimEnd('/')
    } catch (_: java.net.URISyntaxException) { null }

    private fun isLocalHost(host: String): Boolean {
        if (host in setOf("localhost", "[::1]")) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
        val octets = parts.map { it.toInt() }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 127 || octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168)
    }
}
