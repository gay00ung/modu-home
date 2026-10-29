package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.RouteChangeApproval
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.core.planning.JourneyStage

internal data class NavigationResumeConfirmation(
    val plan: NavigationPlan,
    val approval: RouteChangeApproval,
    val state: HomeUiState,
    val interaction: DrivingInteraction,
) {
    val progress get() = state.progress

    fun isCurrent(current: HomeUiState, limits: DrivingInteraction, revision: Long): Boolean =
        current == state && current.progress.stage == JourneyStage.ACTIVE && current.navigationWindow == plan &&
            limits == interaction && limits.editingAllowed && revision == approval.routeRevision
}
