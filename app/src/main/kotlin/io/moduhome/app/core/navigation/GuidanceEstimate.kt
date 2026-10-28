package io.moduhome.app.core.navigation

internal data class GuidancePointObservation(
    val place: ObservedNavigationPlace?,
    val remainingSeconds: Int,
    val remainingMeters: Int,
)

/** Metrics are cumulative from the vehicle to this point, not individual leg costs. */
internal data class GuidancePointEstimate(
    val point: NavigationPoint,
    val remainingSeconds: Int,
    val remainingMeters: Int,
)

internal class GuidanceEstimate private constructor(
    val destination: GuidancePointEstimate,
    val waypoints: List<GuidancePointEstimate>,
    /** Local callback receipt time, not the provider's measurement time. */
    val receivedAtEpochMillis: Long,
    val observedRoute: NavigationSnapshot,
) {
    val source: String = "차량 내비"

    fun isFresh(nowEpochMillis: Long): Boolean = nowEpochMillis >= receivedAtEpochMillis &&
        nowEpochMillis - receivedAtEpochMillis <= FRESHNESS_MILLIS

    companion object {
        const val FRESHNESS_MILLIS = 60_000L
        const val MAX_QUERY_MILLIS = 10_000L

        fun fromObservation(
            plan: NavigationPlan,
            destinationObservation: GuidancePointObservation?,
            waypointObservations: List<GuidancePointObservation>?,
            routeBefore: NavigationSnapshot?,
            routeAfter: NavigationSnapshot?,
            queriedAt: Long,
            receivedAt: Long,
            routeChanged: Boolean = false,
        ): GuidanceEstimateResult {
            fun failed(reason: String) = GuidanceEstimateResult.Failed(reason, routeAfter)
            if (queriedAt < 0 || receivedAt < queriedAt || receivedAt - queriedAt > MAX_QUERY_MILLIS) {
                return failed("안내 시간 응답이 오래됐어요. 다시 확인해주세요")
            }
            if (routeBefore == null || routeAfter == null) {
                return failed("현재 차량 내비의 안내 상태를 확인하지 못했어요")
            }
            if (routeChanged || routeBefore != routeAfter) {
                return failed("조회 중 차량 내비 안내가 바뀌었어요")
            }
            if (!routeAfter.guidanceActive) return failed("차량 내비의 길안내가 종료됐어요")
            if (destinationObservation == null || waypointObservations == null) {
                return failed("차량 내비의 남은 시간을 확인하지 못했어요")
            }
            val snapshotDestination = routeAfter.destination
                ?: return failed("차량 내비 목적지를 확인하지 못했어요")
            if (!snapshotDestination.identifies(plan.destination) ||
                !destinationObservation.identifies(plan.destination, snapshotDestination)) {
                return failed("차량 내비가 안내하는 집이 계획과 달라요")
            }
            val remainingCount = routeAfter.waypoints.size
            if (remainingCount > plan.waypoints.size || waypointObservations.size != remainingCount) {
                return failed("차량 내비 경유지가 계획과 달라요")
            }
            // Only an already-visited prefix may disappear. A middle removal changes the plan.
            val remainingPoints = plan.waypoints.takeLast(remainingCount)
            val estimates = remainingPoints.mapIndexed { index, point ->
                val snapshotPoint = routeAfter.waypoints[index]
                val observation = waypointObservations[index]
                if (!snapshotPoint.identifies(point) || !observation.identifies(point, snapshotPoint)) {
                    return failed("차량 내비 경유지 순서가 계획과 달라요")
                }
                GuidancePointEstimate(point, observation.remainingSeconds, observation.remainingMeters)
            }
            val destination = GuidancePointEstimate(
                plan.destination, destinationObservation.remainingSeconds, destinationObservation.remainingMeters,
            )
            val ordered = estimates + destination
            if (ordered.any { it.remainingSeconds < 0 || it.remainingMeters < 0 ||
                    (it.remainingSeconds == 0 && it.remainingMeters > 0) } ||
                (destination.remainingSeconds == 0 && destination.remainingMeters == 0) ||
                ordered.zipWithNext().any { (first, next) ->
                    first.remainingSeconds > next.remainingSeconds || first.remainingMeters > next.remainingMeters
                }) {
                return failed("차량 내비의 남은 시간과 거리를 확인하지 못했어요")
            }
            return GuidanceEstimateResult.Success(GuidanceEstimate(destination, estimates.toList(), receivedAt,
                routeAfter.copy(waypoints = routeAfter.waypoints.toList())))
        }

        private fun ObservedNavigationPlace.identifies(point: NavigationPoint): Boolean =
            (point.placeId.isEmpty() || placeId == point.placeId) && matches(point)

        private fun GuidancePointObservation.identifies(
            point: NavigationPoint,
            snapshotPlace: ObservedNavigationPlace,
        ): Boolean {
            val actual = place ?: return false
            if (!actual.identifies(point)) return false
            if (snapshotPlace.placeId.isNotEmpty()) {
                return actual.placeId == snapshotPlace.placeId &&
                    (snapshotPlace.provider.isEmpty() || actual.provider.isEmpty() ||
                        snapshotPlace.provider == actual.provider) &&
                    (snapshotPlace.subId.isEmpty() || actual.subId.isEmpty() || snapshotPlace.subId == actual.subId)
            }
            val latitude = snapshotPlace.latitude ?: return false
            val longitude = snapshotPlace.longitude ?: return false
            return actual.matches(NavigationPoint(snapshotPlace.name, latitude, longitude))
        }
    }
}

internal sealed interface GuidanceEstimateResult {
    val observedRoute: NavigationSnapshot?
    data class Success(val estimate: GuidanceEstimate) : GuidanceEstimateResult {
        override val observedRoute: NavigationSnapshot get() = estimate.observedRoute
    }
    data class Failed(val reason: String, override val observedRoute: NavigationSnapshot? = null) : GuidanceEstimateResult
    data object Unsupported : GuidanceEstimateResult {
        override val observedRoute: NavigationSnapshot? = null
    }
}
