package io.moduhome.app.feature.home

import io.moduhome.app.core.model.ErrandKind
import io.moduhome.app.core.model.JourneyMode
import io.moduhome.app.core.planning.TimeBudget
import io.moduhome.app.core.planning.TimeBudgetResult

internal data class HomeUiState(
    val mode: JourneyMode = JourneyMode.TOGETHER,
    val selectedErrands: Set<ErrandKind> = emptySet(),
    val allowedAdditionalMinutes: Int = 30,
) {
    val demoStayMinutes: Int
        get() = selectedErrands.sumOf { if (it == ErrandKind.PICKUP) 5 else 15 }

    val demoExtraDrivingMinutes: Int
        get() = selectedErrands.sumOf { if (it == ErrandKind.PICKUP) 2 else 1 }

    val demoBudgetResult: TimeBudgetResult
        get() = TimeBudget(
            directDrivingMinutes = 40,
            allowedAdditionalMinutes = allowedAdditionalMinutes,
        ).evaluate(
            plannedDrivingMinutes = 40 + demoExtraDrivingMinutes,
            stayMinutes = demoStayMinutes,
        )
}
