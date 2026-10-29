package io.moduhome.app.feature.vehicle

import io.moduhome.app.core.model.JourneyMode
import io.moduhome.app.core.model.JourneyShortcut
import io.moduhome.app.core.model.JourneyShortcutRequest
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.shared.SharedJourneySession

internal fun HomeUiState.journeyShortcut(): JourneyShortcut? {
    if (progress.stage != JourneyStage.ACTIVE) return null
    val started = progress.startedAtMillis?.takeIf { it >= 0 } ?: return null
    val plan = selectedPlan?.takeIf { it.isValid && it.visits.size in 1..2 } ?: return null
    if (progress.completedVisits !in 0..plan.visits.size) return null
    return JourneyShortcut(JourneyShortcutRequest(JourneyMode.SOLO, started), plan.visits.size, progress.completedVisits)
}

internal fun SharedJourneySession.journeyShortcut(): JourneyShortcut? {
    if (progress.stage != JourneyStage.ACTIVE) return null
    val started = progress.startedAtMillis?.takeIf { it >= 0 } ?: return null
    val plan = routing?.takeIf { it.matches(draft) }?.plans
        ?.firstOrNull { it.id == progress.selectedPlanId && it.isValid && it.stops.size in 1..3 } ?: return null
    if (progress.completedStopCount !in 0..plan.stops.size) return null
    return JourneyShortcut(JourneyShortcutRequest(JourneyMode.TOGETHER, started), plan.stops.size, progress.completedStopCount)
}
