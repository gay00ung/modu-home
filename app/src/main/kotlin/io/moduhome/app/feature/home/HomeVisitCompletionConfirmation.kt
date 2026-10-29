package io.moduhome.app.feature.home

import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.RouteChangeApproval
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind

/** A stationary user's completion claim, bound to the itinerary and the route they reviewed. */
internal class HomeVisitCompletionConfirmation private constructor(
    val state: HomeUiState,
    val kind: VisitKind,
    val window: NavigationPlan,
    val approval: RouteChangeApproval,
    val interaction: DrivingInteraction,
) {
    val placeName: String get() = checkNotNull(state.selectedPlan).visits[state.progress.completedVisits].visit.place
    val guidanceEnded: Boolean get() = !approval.snapshot.guidanceActive
    val nextWindow: NavigationPlan? get() = state.selectedPlan?.let {
        state.draft.navigationPlan(it, state.progress.completedVisits + 1)
    }
    val navigationRequired: Boolean get() {
        val next = nextWindow ?: return false
        val inactiveWithUnrecordedWaypoints = guidanceEnded && window.waypoints.isNotEmpty()
        return !approval.snapshot.confirmsSuffixOf(next) && !inactiveWithUnrecordedWaypoints
    }

    fun isCurrent(current: HomeUiState, limits: DrivingInteraction, revision: Long): Boolean =
        current == state && limits == interaction && limits.editingAllowed && revision == approval.routeRevision

    fun afterRead(
        current: HomeUiState,
        limits: DrivingInteraction,
        snapshot: NavigationSnapshot?,
        revisionBefore: Long,
        revisionAfter: Long,
        now: Long,
    ): HomeVisitCompletion? {
        if (!isCurrent(current, limits, revisionBefore) || !approval.matches(snapshot, revisionAfter)) return null
        val progress = current.progress.completeVisit(current.plans, kind, now)
        if (progress == current.progress) return null
        return HomeVisitCompletion(current.copy(progress = progress), approval, navigationRequired)
    }

    companion object {
        fun fromObservation(
            state: HomeUiState,
            kind: VisitKind,
            interaction: DrivingInteraction,
            snapshot: NavigationSnapshot?,
            revisionBefore: Long,
            revisionAfter: Long,
        ): HomeVisitCompletionConfirmation? {
            if (!interaction.editingAllowed || state.progress.stage != JourneyStage.ACTIVE) return null
            val selected = state.selectedPlan?.takeIf { it.isValid } ?: return null
            if (selected.visits.getOrNull(state.progress.completedVisits)?.visit?.kind != kind) return null
            val window = state.navigationWindow ?: return null
            val approval = RouteChangeApproval.fromObservation(snapshot, revisionBefore, revisionAfter) ?: return null
            val observed = approval.snapshot
            // The native navigator may consume a visited prefix before the user records completion.
            val sameRemainingRoute = observed.confirmsSuffixOf(window)
            // Inactive guidance supplies no arrival assertion. Only the user records completion.
            // Previously passed known waypoints stay inactive until an explicit resume approval.
            val endedGuidance = !observed.guidanceActive && observed.waypoints.isEmpty()
            if (!sameRemainingRoute && !endedGuidance) return null
            return HomeVisitCompletionConfirmation(state, kind, window, approval, interaction)
        }
    }
}

internal data class HomeVisitCompletion(
    val state: HomeUiState,
    val approval: RouteChangeApproval,
    val navigationRequired: Boolean,
)
