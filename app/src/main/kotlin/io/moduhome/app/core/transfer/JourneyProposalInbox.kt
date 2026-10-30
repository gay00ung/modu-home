package io.moduhome.app.core.transfer

internal enum class ProposalReviewStatus { PENDING, APPROVED, DECLINED }

internal data class ProposalInboxEntry(
    val proposal: JourneyProposal,
    val status: ProposalReviewStatus = ProposalReviewStatus.PENDING,
)

/** Immutable inbox. A revision replaces the pending review and requires fresh approval. */
internal class JourneyProposalInbox private constructor(entries: List<ProposalInboxEntry>) {
    val entries: List<ProposalInboxEntry> = java.util.Collections.unmodifiableList(ArrayList(entries))

    override fun equals(other: Any?): Boolean = other is JourneyProposalInbox && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "JourneyProposalInbox(entries=${entries.size})"

    fun receive(proposal: JourneyProposal, nowMillis: Long): ProposalReceiveResult {
        proposal.unavailableReason(nowMillis)?.let { return ProposalReceiveResult.Rejected(it) }
        val current = entries.firstOrNull { it.proposal.id == proposal.id }
        if (current != null) {
            if ((current.proposal is ErrandProposal) != (proposal is ErrandProposal)) {
                return ProposalReceiveResult.Rejected("제안 종류가 달라요. 새 제안으로 보내주세요")
            }
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
        return ProposalReceiveResult.Pending(JourneyProposalInbox(withRoom + entry), entry)
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

    fun decline(id: String, revision: Int): JourneyProposalInbox {
        val entry = entries.firstOrNull {
            it.proposal.id == id && it.proposal.revision == revision && it.status == ProposalReviewStatus.PENDING
        } ?: return this
        return withStatus(entry, ProposalReviewStatus.DECLINED)
    }

    private fun withStatus(entry: ProposalInboxEntry, status: ProposalReviewStatus): JourneyProposalInbox =
        JourneyProposalInbox(entries.map { if (it == entry) it.copy(status = status) else it })

    companion object {
        const val MAX_ENTRIES = 20
        fun empty(): JourneyProposalInbox = JourneyProposalInbox(emptyList())

        fun restore(entries: List<ProposalInboxEntry>): JourneyProposalInbox {
            require(entries.size <= MAX_ENTRIES)
            require(entries.map { it.proposal.id }.distinct().size == entries.size)
            return JourneyProposalInbox(entries.toList())
        }
    }
}

internal sealed interface ProposalReceiveResult {
    data class Pending(val inbox: JourneyProposalInbox, val entry: ProposalInboxEntry) : ProposalReceiveResult
    data object Duplicate : ProposalReceiveResult
    data class Rejected(val reason: String, val failure: ProposalReceiveFailure = ProposalReceiveFailure.INVALID) : ProposalReceiveResult
}

internal enum class ProposalReceiveFailure { INVALID, FULL }

internal sealed interface ProposalApprovalResult {
    data class Approved(val inbox: JourneyProposalInbox, val proposal: JourneyProposal) : ProposalApprovalResult
    data class Rejected(val reason: String) : ProposalApprovalResult
}
