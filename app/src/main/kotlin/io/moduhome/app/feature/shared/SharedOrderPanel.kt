package io.moduhome.app.feature.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.moduhome.app.core.shared.SharedJourneyPlan
import io.moduhome.app.core.shared.SharedScheduledStop
import io.moduhome.app.feature.vehicle.OrderArrival
import io.moduhome.app.feature.vehicle.VehicleOrderPanel

@Composable
internal fun SharedOrderPanel(
    plan: SharedJourneyPlan,
    modifier: Modifier = Modifier,
    departureHome: String? = null,
) {
    val confirmingDeparture = departureHome != null
    val placeLines = if (confirmingDeparture) Int.MAX_VALUE else 2
    val extraTime = plan.driverAdditionalMinutes?.let { "직행 +${it}분" }
    val homeDetail = if (confirmingDeparture) listOfNotNull("집 도착", extraTime).joinToString(" · ") else extraTime
    val arrivals = plan.stops.mapIndexed { index, stop ->
        OrderArrival(index + 1, stop.place.point.name, shortStopLabel(stop), stop.arrivalMinute, placeLines)
    } + OrderArrival(null, departureHome ?: "집 도착", homeDetail, plan.driverHomeArrivalMinute, placeLines)
    val title = when {
        plan.stops.any { it is SharedScheduledStop.Errand } -> "방문 순서"
        plan.stops.isNotEmpty() -> "하차 순서"
        else -> "귀가 순서"
    }
    val estimate = when {
        plan.includesSampleTimes -> "예시 시간"
        plan.includesUserEstimates -> "입력한 시간"
        else -> "실시간 교통 제외"
    }
    VehicleOrderPanel(
        arrivals = arrivals,
        estimate = estimate,
        title = title.takeUnless { confirmingDeparture },
        animate = !confirmingDeparture && plan.stops.isNotEmpty(),
        modifier = modifier,
    )
}
