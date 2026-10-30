package io.moduhome.app.feature.home

import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.JourneyProposalInbox

/** Original user suggestion and its approval time, separate from subsequent manual edits. */
internal data class HomeProposalAcceptance(val proposal: ErrandProposal, val appliedAtMillis: Long) {
    init {
        require(appliedAtMillis >= 0 && proposal.unavailableReason(appliedAtMillis) == null)
    }
}

internal fun validHomeProposalHistory(history: List<HomeProposalAcceptance>): Boolean =
    history.size <= JourneyProposalInbox.MAX_ENTRIES && history.map { it.proposal.id }.distinct().size == history.size

internal sealed interface HomeProposalApplyResult {
    data class Applied(val state: HomeUiState, val inbox: JourneyProposalInbox) : HomeProposalApplyResult
    data class AlreadyApplied(val inbox: JourneyProposalInbox) : HomeProposalApplyResult
    data class Rejected(val reason: String) : HomeProposalApplyResult
}

/** Persists the draft and receipt together before acknowledging the separate inbox. */
internal class HomeProposalCommitter(
    private val saveJourney: (HomeUiState) -> Boolean,
    private val saveInbox: (JourneyProposalInbox) -> Boolean,
) {
    fun commit(change: HomeProposalApplyResult.Applied): HomeProposalCommitResult = when {
        !saveJourney(change.state) -> HomeProposalCommitResult.JOURNEY_FAILED
        !saveInbox(change.inbox) -> HomeProposalCommitResult.INBOX_PENDING
        else -> HomeProposalCommitResult.COMPLETE
    }
}

internal enum class HomeProposalCommitResult { JOURNEY_FAILED, INBOX_PENDING, COMPLETE }
