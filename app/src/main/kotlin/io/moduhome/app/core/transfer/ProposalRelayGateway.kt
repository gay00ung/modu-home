package io.moduhome.app.core.transfer

/** Delivery ends at the recipient's durable inbox. It never approves a journey. */
internal interface ProposalRelayGateway {
    suspend fun openRoom(seedLink: String? = null): RelayResult<RelayReceiverSession>
    suspend fun join(session: RelaySenderSession): RelayResult<RelayRoomWelcome>
    suspend fun pending(session: RelayReceiverSession): RelayResult<List<RelayMessage>>
    suspend fun acknowledge(session: RelayReceiverSession, messageId: String, status: RelayDeliveryStatus): RelayResult<Unit>
    suspend fun send(session: RelaySenderSession, message: RelayMessage): RelayResult<RelayDeliveryStatus>
    suspend fun delivery(session: RelaySenderSession, messageId: String): RelayResult<RelayDeliveryStatus>
    suspend fun close(session: RelayReceiverSession): RelayResult<Unit>
}

/** These credentials deliberately have no persistence representation or revealing toString. */
internal class RelaySenderSession(
    val baseUrl: String,
    val roomId: String,
    val senderToken: String,
    val expiresAtMillis: Long,
) {
    init {
        require(baseUrl.length in 1..2_048 && RelayIdentifiers.validId(roomId))
        require(RelayIdentifiers.validToken(senderToken) && expiresAtMillis > 0)
    }
    override fun toString(): String = "RelaySenderSession(credentials=redacted)"
}

internal class RelayReceiverSession(
    val baseUrl: String,
    val roomId: String,
    val receiverToken: String,
    val sender: RelaySenderSession,
) {
    val expiresAtMillis: Long get() = sender.expiresAtMillis
    init {
        require(baseUrl.length in 1..2_048 && roomId == sender.roomId)
        require(RelayIdentifiers.validToken(receiverToken) && receiverToken != sender.senderToken)
    }
    override fun toString(): String = "RelayReceiverSession(credentials=redacted)"
}

internal data class RelayRoomWelcome(val expiresAtMillis: Long, val seedLink: String?) {
    init {
        require(expiresAtMillis > 0)
        require(seedLink == null || RelayIdentifiers.validLink(seedLink))
    }
    override fun toString(): String = "RelayRoomWelcome(expiresAtMillis=$expiresAtMillis, hasSeed=${seedLink != null})"
}

internal data class RelayMessage(val messageId: String, val link: String) {
    init { require(RelayIdentifiers.validId(messageId) && RelayIdentifiers.validLink(link)) }
    override fun toString(): String = "RelayMessage(messageId=$messageId, payload=redacted)"
}

internal enum class RelayDeliveryStatus { QUEUED, STORED, REJECTED }
internal enum class RelayFailure { CONFIGURATION, CONNECTION, CLOSED, UNAUTHORIZED, FULL, CONFLICT, INVALID_RESPONSE }
internal sealed interface RelayResult<out T> {
    data class Success<T>(val value: T) : RelayResult<T>
    data class Failure(val code: RelayFailure, val reason: String) : RelayResult<Nothing>
}

internal object RelayIdentifiers {
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")
    fun validId(value: String): Boolean = idPattern.matches(value)
    fun validToken(value: String): Boolean = tokenPattern.matches(value)
    fun validLink(value: String): Boolean = value.length in 1..8_192 && value.all { it.code in 0x21..0x7e }
}
