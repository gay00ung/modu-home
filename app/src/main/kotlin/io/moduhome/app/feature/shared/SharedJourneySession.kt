package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.remainingAfter
import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.shared.SharedJourneyProgress
import io.moduhome.app.core.shared.currentWindow
import io.moduhome.app.core.transfer.JourneyProposalInbox
import io.moduhome.app.core.transfer.ProposalReviewStatus
import java.time.ZoneId

internal data class SharedJourneySession(
    val draft: SharedJourneyDraft = SharedJourneyDraft(),
    val progress: SharedJourneyProgress = SharedJourneyProgress(),
    val routing: SharedRoutedJourneyEstimates? = null,
    val acceptedProposals: List<SharedProposalAcceptance> = emptyList(),
) {
    fun resetToInput(draft: SharedJourneyDraft = this.draft): SharedJourneySession =
        copy(draft = draft, progress = SharedJourneyProgress(), routing = null)

    fun reviewProposal(
        inbox: JourneyProposalInbox,
        id: String,
        revision: Int,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SharedProposalReviewResult = SharedProposalReview.prepare(this, inbox, id, revision, nowMillis, zoneId)

    fun approveProposal(
        review: SharedProposalReview,
        inbox: JourneyProposalInbox,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SharedProposalApplyResult = review.approve(this, inbox, nowMillis, zoneId)

    /** Repairs a delayed inbox write from a durable acceptance, without accepting any new value. */
    fun reconcileProposalInbox(inbox: JourneyProposalInbox): JourneyProposalInbox {
        if (!validSharedProposalHistory(acceptedProposals)) return inbox
        val entries = inbox.entries.map { entry ->
            if (entry.status == ProposalReviewStatus.PENDING && acceptedProposals.any { it.proposal == entry.proposal }) {
                entry.copy(status = ProposalReviewStatus.APPROVED)
            } else entry
        }
        return if (entries == inbox.entries) inbox else JourneyProposalInbox.restore(entries)
    }

    companion object {
        /** Validates persisted state without sending or approving any navigation request. */
        fun restore(
            draft: SharedJourneyDraft,
            progress: SharedJourneyProgress,
            routing: SharedRoutedJourneyEstimates?,
            nowMillis: Long,
            acceptedProposals: List<SharedProposalAcceptance> = emptyList(),
        ): SharedJourneySession {
            if (!validSharedProposalHistory(acceptedProposals)) return SharedJourneySession(draft)
            val history = acceptedProposals.toList()
            fun input() = SharedJourneySession(draft, acceptedProposals = history)
            if (nowMillis < 0 || history.any { it.appliedAtMillis > nowMillis }) return input()
            if (progress.stage == JourneyStage.INPUT) {
                return if (progress == SharedJourneyProgress() && routing?.matches(draft) == true &&
                    routing.validatedAtMillis <= nowMillis && routing.isFresh(nowMillis)) {
                    SharedJourneySession(draft, routing = routing, acceptedProposals = history)
                } else input()
            }
            if (routing == null || !routing.matches(draft) || routing.validatedAtMillis > nowMillis) return input()
            val plan = routing.plans.firstOrNull { it.id == progress.selectedPlanId } ?: return input()
            if (progress.completedStopCount > plan.stops.size) return input()
            if (progress.completedAtMillis.any { it > nowMillis }) return input()
            return when (progress.stage) {
                JourneyStage.INPUT -> input()
                JourneyStage.COMPARE, JourneyStage.REVIEW -> {
                    if (!routing.isFresh(nowMillis) || progress.completedStopCount != 0 || progress.startedAtMillis != null ||
                        progress.confirmedWindow != null || progress.pendingWindow != null || progress.driverHome != null ||
                        (progress.stage == JourneyStage.REVIEW && !plan.isValid)) input()
                    else SharedJourneySession(draft, progress, routing, history)
                }
                JourneyStage.ACTIVE, JourneyStage.COMPLETE -> {
                    val started = progress.startedAtMillis
                    if (!plan.isValid || started == null || started < routing.validatedAtMillis || started > nowMillis) return input()
                    val home = draft.driverHome ?: return input()
                    if (progress.driverHome != null && progress.driverHome != home) return input()
                    // Older records used home as every window's destination. Validate that exact
                    // layout before migrating intent; no historical confirmation survives restart.
                    fun recordedWindow(completed: Int): NavigationPlan = if (progress.driverHome == null) {
                        NavigationPlan(home, plan.stops.drop(completed).take(2).map { it.place.point })
                    } else plan.currentWindow(home, completed)
                    val current = recordedWindow(progress.completedStopCount)
                    val previous = recordedWindow((progress.completedStopCount - 1).coerceAtLeast(0))
                    val observedPreviousSuffixes = (1..minOf(2, progress.completedStopCount)).mapNotNull { count ->
                        val priorWindow = recordedWindow(progress.completedStopCount - count)
                        priorWindow.takeIf { count <= it.waypoints.size }?.remainingAfter(count)
                    }
                    val pending = progress.pendingWindow
                    val confirmed = progress.confirmedWindow
                    if ((pending != null && pending != current) ||
                        (confirmed != null && confirmed != current && confirmed != previous && confirmed !in observedPreviousSuffixes) ||
                        (pending == null && confirmed != current)) return input()
                    if (progress.stage == JourneyStage.COMPLETE) {
                        if (progress.completedStopCount != plan.stops.size || (pending ?: confirmed) != current) input()
                        else SharedJourneySession(draft, progress.copy(driverHome = home), routing, history)
                    } else SharedJourneySession(draft, progress.copy(driverHome = home,
                        confirmedWindow = null, pendingWindow = plan.currentWindow(home, progress.completedStopCount)), routing, history)
                }
            }
        }
    }
}
