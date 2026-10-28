package io.moduhome.app.core.navigation

/** A single destination estimate from the vehicle's current position, without stop time. */
internal data class TripEstimate(
    val destination: NavigationPoint,
    val durationSeconds: Int,
    val distanceMeters: Int,
    /** Local request time, not the provider's traffic-data update time. */
    val queriedAtEpochMillis: Long,
) {
    init {
        require(durationSeconds >= 0)
        require(distanceMeters >= 0)
        require(queriedAtEpochMillis >= 0)
    }

    fun isFresh(nowEpochMillis: Long): Boolean =
        nowEpochMillis >= queriedAtEpochMillis && nowEpochMillis - queriedAtEpochMillis <= FRESHNESS_MILLIS

    companion object {
        const val FRESHNESS_MILLIS = 5 * 60 * 1_000L

        fun fromObservation(
            destination: NavigationPoint,
            observedDestination: ObservedNavigationPlace?,
            durationSeconds: Int,
            distanceMeters: Int,
            queriedAtEpochMillis: Long,
            observedAtEpochMillis: Long,
            routeBefore: NavigationSnapshot?,
            routeAfter: NavigationSnapshot?,
        ): TripEstimateResult {
            if (observedDestination?.matchesTripDestination(destination) != true) {
                return TripEstimateResult.Failed("요청한 장소의 이동시간인지 확인하지 못했어요")
            }
            if (durationSeconds < 0 || distanceMeters < 0) {
                return TripEstimateResult.Failed("내비가 올바른 이동시간과 거리를 반환하지 않았어요")
            }
            if (queriedAtEpochMillis < 0 || observedAtEpochMillis < queriedAtEpochMillis ||
                observedAtEpochMillis - queriedAtEpochMillis > FRESHNESS_MILLIS) {
                return TripEstimateResult.Failed("이동시간 응답이 오래됐어요. 다시 조회해주세요")
            }
            if (routeBefore == null || routeAfter == null) {
                return TripEstimateResult.Failed("조회 전후의 차량 내비 상태를 확인하지 못했어요")
            }
            if (routeBefore != routeAfter) {
                return TripEstimateResult.Failed("조회 중 차량 내비 경로가 바뀌었어요. 현재 안내를 확인해주세요")
            }
            return TripEstimateResult.Success(
                TripEstimate(destination, durationSeconds, distanceMeters, queriedAtEpochMillis),
            )
        }
    }
}

/** A known POI must be returned as that POI; a nearby shop's coordinate is insufficient. */
internal fun ObservedNavigationPlace.matchesTripDestination(expected: NavigationPoint): Boolean =
    (expected.placeId.isEmpty() || placeId == expected.placeId) && matches(expected)

internal sealed interface TripEstimateResult {
    data class Success(val estimate: TripEstimate) : TripEstimateResult
    data object Unsupported : TripEstimateResult
    data class Failed(val reason: String) : TripEstimateResult
}
