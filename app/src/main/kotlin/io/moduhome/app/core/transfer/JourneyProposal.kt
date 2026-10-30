package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint

/** A user suggestion. Delivery alone never changes a journey or establishes sender identity. */
internal sealed interface JourneyProposal {
    val id: String
    val revision: Int
    val createdAtMillis: Long
    val expiresAtMillis: Long
    val place: NavigationPoint
    val deadlineAtMillis: Long?

    fun unavailableReason(nowMillis: Long): String? {
        require(nowMillis >= 0)
        return when {
            createdAtMillis > nowMillis && createdAtMillis - nowMillis > 5 * 60 * 1000L ->
                "제안의 생성 시각을 확인해주세요"
            nowMillis >= expiresAtMillis -> "제안의 유효시간이 지났어요"
            deadlineAtMillis?.let { nowMillis > it } == true -> when (this) {
                is ErrandProposal -> "볼일의 마감 시각이 지났어요"
                is DropoffProposal -> "도착 희망 시각이 지났어요"
            }
            else -> null
        }
    }

    companion object {
        const val MAX_LIFETIME_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}

/** Arrival is an optional hard planning constraint, never a promise of actual traffic conditions. */
internal data class DropoffProposal(
    override val id: String,
    override val revision: Int,
    override val createdAtMillis: Long,
    override val expiresAtMillis: Long,
    override val place: NavigationPoint,
    val passengerName: String = "동승자",
    override val deadlineAtMillis: Long? = null,
) : JourneyProposal {
    init {
        requireProposalFields(this)
        require(passengerName.isNotBlank() && proposalTextValid(passengerName, 32))
    }

    override fun toString(): String = "DropoffProposal(id=$id, revision=$revision, conditions=redacted)"
}

internal fun requireProposalFields(proposal: JourneyProposal) {
    require(proposal.id.matches(Regex("[A-Za-z0-9_-]{8,64}")))
    require(proposal.revision in 1..100_000)
    require(proposal.createdAtMillis >= 0 && proposal.expiresAtMillis > proposal.createdAtMillis)
    require(proposal.expiresAtMillis - proposal.createdAtMillis <= JourneyProposal.MAX_LIFETIME_MILLIS)
    require(proposal.deadlineAtMillis?.let {
        it >= proposal.createdAtMillis && it - proposal.createdAtMillis <= 48 * 60 * 60 * 1000L
    } != false)
    require(proposalTextValid(proposal.place.name, 120) && proposalTextValid(proposal.place.address, 300))
    require(listOf(proposal.place.placeId, proposal.place.subId, proposal.place.provider).all { proposalTextValid(it, 120) })
    require(proposal.place.latitude.isFinite() && proposal.place.longitude.isFinite())
    require(proposal.place.latitude != 0.0 || proposal.place.longitude != 0.0)
}

private fun proposalTextValid(value: String, maximumLength: Int): Boolean =
    value.length <= maximumLength && value.none { it.isISOControl() || it == '\u007f' } &&
        value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value
