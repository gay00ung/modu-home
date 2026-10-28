package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.RouteChangeApproval
import io.moduhome.app.core.planning.JourneyProgress

internal data class NavigationResumeConfirmation(
    val plan: NavigationPlan,
    val approval: RouteChangeApproval,
    val progress: JourneyProgress,
)
