package io.moduhome.app.data.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposal
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Local, unsigned transfer format. No network request or sender identity is implied. */
internal object ProposalLinkCodec {
    const val SCHEME = "modu-home"
    const val HOST = "proposal"
    const val MAX_LINK_LENGTH = 8_192
    private const val VERSION = "1"
    private val keys = setOf("v", "id", "r", "created", "expires", "kind", "name", "lat", "lon",
        "address", "placeId", "subId", "provider", "stay", "walk", "ready", "deadline")

    fun encode(proposal: ErrandProposal): String {
        val values = linkedMapOf(
            "v" to VERSION,
            "id" to proposal.id,
            "r" to proposal.revision.toString(),
            "created" to proposal.createdAtMillis.toString(),
            "expires" to proposal.expiresAtMillis.toString(),
            "kind" to proposal.kind.name,
            "name" to proposal.place.name,
            "lat" to proposal.place.latitude.toString(),
            "lon" to proposal.place.longitude.toString(),
            "address" to proposal.place.address,
            "placeId" to proposal.place.placeId,
            "subId" to proposal.place.subId,
            "provider" to proposal.place.provider,
            "stay" to proposal.stayMinutes.toString(),
            "walk" to proposal.walkingMinutesOneWay.toString(),
            "ready" to if (proposal.ready) "1" else "0",
            "deadline" to proposal.deadlineAtMillis?.toString().orEmpty(),
        )
        val link = "$SCHEME://$HOST?" + values.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        require(link.length <= MAX_LINK_LENGTH)
        return link
    }

    fun decode(link: String, nowMillis: Long): ProposalDecodeResult {
        require(nowMillis >= 0)
        if (link.length > MAX_LINK_LENGTH) return ProposalDecodeResult.Invalid("제안 링크가 너무 길어요")
        if (link.any { it.code !in 0x21..0x7e }) return invalidLink()
        return try {
            val uri = URI(link)
            if (uri.scheme != SCHEME || uri.host != HOST || uri.rawPath.orEmpty().isNotEmpty() ||
                uri.rawFragment != null || uri.rawUserInfo != null || uri.port != -1) return invalidLink()
            val parts = uri.rawQuery?.split('&') ?: return invalidLink()
            if (parts.size != keys.size) return invalidLink()
            val fields = linkedMapOf<String, String>()
            for (part in parts) {
                val split = part.indexOf('=')
                if (split < 1) return invalidLink()
                val key = decodePart(part.substring(0, split))
                if (key !in keys || fields.containsKey(key)) return invalidLink()
                fields[key] = decodePart(part.substring(split + 1))
            }
            if (fields.keys != keys) return invalidLink()
            if (fields.getValue("v") != VERSION) {
                return ProposalDecodeResult.Invalid("이 제안 형식은 앱 업데이트 후 열 수 있어요")
            }
            val deadline = fields.getValue("deadline")
            val ready = when (fields.getValue("ready")) {
                "1" -> true
                "0" -> false
                else -> return invalidLink()
            }
            val proposal = ErrandProposal(
                id = fields.getValue("id"),
                revision = fields.getValue("r").toInt(),
                createdAtMillis = fields.getValue("created").toLong(),
                expiresAtMillis = fields.getValue("expires").toLong(),
                kind = VisitKind.valueOf(fields.getValue("kind")),
                place = NavigationPoint(
                    name = fields.getValue("name"),
                    latitude = fields.getValue("lat").toDouble(),
                    longitude = fields.getValue("lon").toDouble(),
                    address = fields.getValue("address"),
                    placeId = fields.getValue("placeId"),
                    subId = fields.getValue("subId"),
                    provider = fields.getValue("provider"),
                ),
                stayMinutes = fields.getValue("stay").toInt(),
                walkingMinutesOneWay = fields.getValue("walk").toInt(),
                ready = ready,
                deadlineAtMillis = if (deadline.isEmpty()) null else deadline.toLong(),
            )
            val unavailable = proposal.unavailableReason(nowMillis)
            if (unavailable == null) ProposalDecodeResult.Valid(proposal)
            else ProposalDecodeResult.Invalid(unavailable)
        } catch (_: IllegalArgumentException) {
            invalidLink()
        } catch (_: java.net.URISyntaxException) {
            invalidLink()
        } catch (_: CharacterCodingException) {
            invalidLink()
        }
    }

    private fun invalidLink(): ProposalDecodeResult.Invalid = ProposalDecodeResult.Invalid("제안 링크의 내용을 확인해주세요")

    private fun decodePart(raw: String): String {
        val bytes = ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            when (val char = raw[index]) {
                '%' -> {
                    require(index + 2 < raw.length)
                    val high = raw[index + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException()
                    val low = raw[index + 2].digitToIntOrNull(16) ?: throw IllegalArgumentException()
                    bytes.write(high * 16 + low)
                    index += 3
                }
                '+' -> { bytes.write(' '.code); index += 1 }
                else -> { bytes.write(char.code); index += 1 }
            }
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}

internal sealed interface ProposalDecodeResult {
    data class Valid(val proposal: ErrandProposal) : ProposalDecodeResult
    data class Invalid(val reason: String) : ProposalDecodeResult
}
