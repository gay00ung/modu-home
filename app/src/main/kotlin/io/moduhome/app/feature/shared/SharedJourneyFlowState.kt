package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.GuidanceEstimateResult
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.RouteChangeApproval
import io.moduhome.app.core.shared.SharedJourneyPlan
import io.moduhome.app.core.shared.SharedJourneyProgress
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.feature.home.PlacePickerUiState

/** Owned by ModuHomeApp. Only session is persisted; the rest belongs to the visible flow. */
internal data class SharedJourneyFlowState(
    val session: SharedJourneySession = SharedJourneySession(),
    val planning: Boolean = false,
    val sending: Boolean = false,
    val reading: Boolean = false,
    val polling: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val picker: SharedPlaceSelection? = null,
    val confirmation: SharedRouteConfirmation? = null,
    val passedStopConfirmation: SharedPassedStopConfirmation? = null,
    val terminalStopConfirmation: SharedTerminalStopConfirmation? = null,
    val cancelRequested: Boolean = false,
    val finishConfirmation: SharedJourneyProgress? = null,
    val planPage: Int = 0,
    val operationRevision: Long = 0,
    val snapshot: NavigationSnapshot? = null,
    val observedRouteRevision: Long? = null,
    val guidanceResult: GuidanceEstimateResult? = null,
    val guidanceNowMillis: Long = 0L,
    val errors: List<String> = emptyList(),
) {
    val busy: Boolean get() = planning || sending || reading || polling
    val dialogOpen: Boolean get() = picker != null || confirmation != null || passedStopConfirmation != null || terminalStopConfirmation != null ||
        cancelRequested || finishConfirmation != null
}

internal sealed interface SharedPlaceTarget {
    data object DriverHome : SharedPlaceTarget
    data class Passenger(val id: String) : SharedPlaceTarget
    data object Errand : SharedPlaceTarget
}

internal data class SharedPlaceSelection(
    val target: SharedPlaceTarget,
    val input: SharedJourneyDraft,
    val ui: PlacePickerUiState,
)

internal data class SharedRouteConfirmation(
    val plan: NavigationPlan,
    val approval: RouteChangeApproval,
    val session: SharedJourneySession,
)

/** A native route suffix is evidence of the current guidance, never proof of a physical stop. */
internal data class SharedPassedStopConfirmation(
    val session: SharedJourneySession,
    val plan: SharedJourneyPlan,
    val count: Int,
    val snapshot: NavigationSnapshot,
    val routeRevision: Long,
    val interaction: DrivingInteraction,
)

/** The readback proves only that guidance is inactive; physical completion comes from the user. */
internal data class SharedTerminalStopConfirmation(
    val session: SharedJourneySession,
    val plan: SharedJourneyPlan,
    val snapshot: NavigationSnapshot,
    val routeRevision: Long,
    val interaction: DrivingInteraction,
) {
    fun approvalAfterRead(
        currentSession: SharedJourneySession,
        currentInteraction: DrivingInteraction,
        currentSnapshot: NavigationSnapshot?,
        revisionBefore: Long,
        revisionAfter: Long,
    ): RouteChangeApproval? {
        val selected = currentSession.routing?.takeIf { it.matches(currentSession.draft) }?.plans
            ?.firstOrNull { it.id == currentSession.progress.selectedPlanId }
        if (currentSession != session || selected != plan || currentInteraction != interaction ||
            !currentInteraction.editingAllowed || revisionBefore != routeRevision || currentSnapshot != snapshot ||
            !currentSession.progress.canConfirmTerminalStop(plan, currentSnapshot)) return null
        return RouteChangeApproval.fromObservation(currentSnapshot, revisionBefore, revisionAfter)
    }
}
