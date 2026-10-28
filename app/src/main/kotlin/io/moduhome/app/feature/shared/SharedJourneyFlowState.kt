package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPlan
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
    val cancelRequested: Boolean = false,
    val finishConfirmation: SharedJourneyProgress? = null,
    val planPage: Int = 0,
    val operationRevision: Long = 0,
    val snapshot: NavigationSnapshot? = null,
    val observedRouteRevision: Long? = null,
    val errors: List<String> = emptyList(),
) {
    val busy: Boolean get() = planning || sending || reading || polling
    val dialogOpen: Boolean get() = picker != null || confirmation != null || passedStopConfirmation != null ||
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
