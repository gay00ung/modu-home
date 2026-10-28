package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind

/** A suggestion to review. Receiving this value does not approve or start a route. */
internal data class ErrandProposal(
    val id: String,
    val revision: Int,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val kind: VisitKind,
    val place: NavigationPoint,
    val stayMinutes: Int,
    val walkingMinutesOneWay: Int = 0,
    val ready: Boolean = kind != VisitKind.PICKUP,
    val deadlineAtMillis: Long? = null,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        require(revision in 1..100_000)
        require(createdAtMillis >= 0 && expiresAtMillis > createdAtMillis)
        require(expiresAtMillis - createdAtMillis <= MAX_LIFETIME_MILLIS)
        require(stayMinutes in 0..180 && walkingMinutesOneWay in 0..30)
        require(deadlineAtMillis == null ||
            (deadlineAtMillis >= createdAtMillis && deadlineAtMillis - createdAtMillis <= MAX_DEADLINE_MILLIS))
        require(validText(place.name, 120) && validText(place.address, 300))
        require(listOf(place.placeId, place.subId, place.provider).all { validText(it, 120) })
        require(place.latitude.isFinite() && place.longitude.isFinite())
        require(place.latitude != 0.0 || place.longitude != 0.0)
    }

    fun unavailableReason(nowMillis: Long): String? {
        require(nowMillis >= 0)
        return when {
            createdAtMillis > nowMillis && createdAtMillis - nowMillis > CLOCK_SKEW_MILLIS ->
                "제안의 생성 시각을 확인해주세요"
            nowMillis >= expiresAtMillis -> "제안의 유효시간이 지났어요"
            deadlineAtMillis != null && nowMillis > deadlineAtMillis -> "볼일의 마감 시각이 지났어요"
            else -> null
        }
    }

    companion object {
        const val MAX_LIFETIME_MILLIS: Long = 24 * 60 * 60 * 1000L
        private const val MAX_DEADLINE_MILLIS: Long = 48 * 60 * 60 * 1000L
        private const val CLOCK_SKEW_MILLIS: Long = 5 * 60 * 1000L

        private fun validText(value: String, maximumLength: Int): Boolean =
            value.length <= maximumLength && value.none { it.isISOControl() || it == '\u007f' } &&
                value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value
    }
}

internal enum class ProposalReviewStatus { PENDING, APPROVED, DECLINED }

internal data class ProposalInboxEntry(
    val proposal: ErrandProposal,
    val status: ProposalReviewStatus = ProposalReviewStatus.PENDING,
)

/** Immutable inbox. A revision replaces the pending review and requires fresh approval. */
internal data class ErrandProposalInbox private constructor(val entries: List<ProposalInboxEntry>) {
    fun receive(proposal: ErrandProposal, nowMillis: Long): ProposalReceiveResult {
        proposal.unavailableReason(nowMillis)?.let { return ProposalReceiveResult.Rejected(it) }
        val current = entries.firstOrNull { it.proposal.id == proposal.id }
        if (current != null) {
            if (proposal.revision < current.proposal.revision) {
                return ProposalReceiveResult.Rejected("이미 더 최신 제안을 받았어요")
            }
            if (proposal.revision == current.proposal.revision) {
                return if (proposal == current.proposal) ProposalReceiveResult.Duplicate
                else ProposalReceiveResult.Rejected("같은 버전의 제안 내용이 달라요. 새 제안으로 보내주세요")
            }
        }
        val retained = entries.filter { it.proposal.id != proposal.id && it.proposal.expiresAtMillis > nowMillis }
        // Keep every unread suggestion; an old reviewed receipt can make room for a new one.
        val oldestReviewed = retained.indexOfFirst { it.status != ProposalReviewStatus.PENDING }
        val withRoom = if (retained.size < MAX_ENTRIES) retained else {
            if (oldestReviewed < 0) return ProposalReceiveResult.Rejected("받은 제안을 먼저 정리해주세요", ProposalReceiveFailure.FULL)
            retained.filterIndexed { index, _ -> index != oldestReviewed }
        }
        val entry = ProposalInboxEntry(proposal)
        return ProposalReceiveResult.Pending(ErrandProposalInbox(withRoom + entry), entry)
    }

    fun approve(id: String, revision: Int, nowMillis: Long): ProposalApprovalResult {
        val entry = entries.firstOrNull { it.proposal.id == id }
            ?: return ProposalApprovalResult.Rejected("검토할 제안을 찾지 못했어요")
        if (entry.proposal.revision != revision) {
            return ProposalApprovalResult.Rejected("제안이 수정됐어요. 최신 내용을 다시 확인해주세요")
        }
        if (entry.status != ProposalReviewStatus.PENDING) {
            return ProposalApprovalResult.Rejected("이미 검토한 제안이에요")
        }
        entry.proposal.unavailableReason(nowMillis)?.let { return ProposalApprovalResult.Rejected(it) }
        return ProposalApprovalResult.Approved(withStatus(entry, ProposalReviewStatus.APPROVED), entry.proposal)
    }

    fun decline(id: String, revision: Int): ErrandProposalInbox {
        val entry = entries.firstOrNull {
            it.proposal.id == id && it.proposal.revision == revision && it.status == ProposalReviewStatus.PENDING
        } ?: return this
        return withStatus(entry, ProposalReviewStatus.DECLINED)
    }

    private fun withStatus(entry: ProposalInboxEntry, status: ProposalReviewStatus): ErrandProposalInbox =
        ErrandProposalInbox(entries.map { if (it == entry) it.copy(status = status) else it })

    companion object {
        const val MAX_ENTRIES = 20
        fun empty(): ErrandProposalInbox = ErrandProposalInbox(emptyList())

        fun restore(entries: List<ProposalInboxEntry>): ErrandProposalInbox {
            require(entries.size <= MAX_ENTRIES)
            require(entries.map { it.proposal.id }.distinct().size == entries.size)
            return ErrandProposalInbox(entries.toList())
        }
    }
}

internal sealed interface ProposalReceiveResult {
    data class Pending(val inbox: ErrandProposalInbox, val entry: ProposalInboxEntry) : ProposalReceiveResult
    data object Duplicate : ProposalReceiveResult
    data class Rejected(val reason: String, val failure: ProposalReceiveFailure = ProposalReceiveFailure.INVALID) : ProposalReceiveResult
}

internal enum class ProposalReceiveFailure { INVALID, FULL }

internal sealed interface ProposalApprovalResult {
    data class Approved(val inbox: ErrandProposalInbox, val proposal: ErrandProposal) : ProposalApprovalResult
    data class Rejected(val reason: String) : ProposalApprovalResult
}
