package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.remainingAfter
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.shared.SharedJourneyProgress
import io.moduhome.app.core.shared.currentWindow

internal data class SharedJourneySession(
    val draft: SharedJourneyDraft = SharedJourneyDraft(),
    val progress: SharedJourneyProgress = SharedJourneyProgress(),
    val routing: SharedRoutedJourneyEstimates? = null,
) {
    companion object {
        /** Validates persisted state without sending or approving any navigation request. */
        fun restore(
            draft: SharedJourneyDraft,
            progress: SharedJourneyProgress,
            routing: SharedRoutedJourneyEstimates?,
            nowMillis: Long,
        ): SharedJourneySession {
            fun input() = SharedJourneySession(draft)
            if (nowMillis < 0) return input()
            if (progress.stage == JourneyStage.INPUT) {
                return if (progress == SharedJourneyProgress() && routing?.matches(draft) == true &&
                    routing.validatedAtMillis <= nowMillis && routing.isFresh(nowMillis)) {
                    SharedJourneySession(draft, routing = routing)
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
                        progress.confirmedWindow != null || progress.pendingWindow != null ||
                        (progress.stage == JourneyStage.REVIEW && !plan.isValid)) input()
                    else SharedJourneySession(draft, progress, routing)
                }
                JourneyStage.ACTIVE, JourneyStage.COMPLETE -> {
                    val started = progress.startedAtMillis
                    if (!plan.isValid || started == null || started < routing.validatedAtMillis || started > nowMillis) return input()
                    val home = draft.driverHome ?: return input()
                    val current = plan.currentWindow(home, progress.completedStopCount)
                    val previous = plan.currentWindow(home, (progress.completedStopCount - 1).coerceAtLeast(0))
                    val observedPreviousSuffixes = (1..minOf(2, progress.completedStopCount)).mapNotNull { count ->
                        val priorWindow = plan.currentWindow(home, progress.completedStopCount - count)
                        priorWindow.takeIf { count <= it.waypoints.size }?.remainingAfter(count)
                    }
                    val pending = progress.pendingWindow
                    val confirmed = progress.confirmedWindow
                    if ((pending != null && pending != current) ||
                        (confirmed != null && confirmed != current && confirmed != previous && confirmed !in observedPreviousSuffixes) ||
                        (pending == null && confirmed != current)) return input()
                    if (progress.stage == JourneyStage.COMPLETE) {
                        if (progress.completedStopCount != plan.stops.size || (pending ?: confirmed) != current) input()
                        else SharedJourneySession(draft, progress, routing)
                    } else SharedJourneySession(draft, progress.afterRestart(plan, home), routing)
                }
            }
        }
    }
}
