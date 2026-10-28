package io.moduhome.app.core.routing

import io.moduhome.app.core.navigation.NavigationPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal interface RouteEstimateGateway {
    /** One response belongs to this entire ordered route, not to a reusable pairwise matrix. */
    suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult
}

internal data class RouteEstimateLeg(
    val from: NavigationPoint,
    val to: NavigationPoint,
    val durationSeconds: Double,
    val distanceMeters: Double,
) {
    init {
        require(durationSeconds.isFinite() && durationSeconds >= 0)
        require(distanceMeters.isFinite() && distanceMeters >= 0)
    }
}

internal data class ObservedRouteLeg(val durationSeconds: Double, val distanceMeters: Double)

internal data class SnappedRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
)

/** A road-network estimate. Stop time and live traffic are not included. */
internal class RouteEstimate private constructor(
    val points: List<NavigationPoint>,
    val legs: List<RouteEstimateLeg>,
    val snappedPoints: List<SnappedRoutePoint>,
    val queriedAtEpochMillis: Long,
    val dataVersion: String?,
) {
    val source: String get() = "OSRM · OpenStreetMap"
    val includesLiveTraffic: Boolean get() = false
    val durationSeconds: Double get() = legs.sumOf { it.durationSeconds }
    val distanceMeters: Double get() = legs.sumOf { it.distanceMeters }

    fun isFresh(nowEpochMillis: Long): Boolean =
        nowEpochMillis >= queriedAtEpochMillis && nowEpochMillis - queriedAtEpochMillis <= FRESHNESS_MILLIS

    companion object {
        const val MAX_ROUTE_POINTS = 6
        const val MAX_SNAP_METERS = 250.0
        const val FRESHNESS_MILLIS = 5 * 60 * 1_000L

        fun fromObservation(
            points: List<NavigationPoint>,
            observedLegs: List<ObservedRouteLeg>,
            snappedPoints: List<SnappedRoutePoint>,
            durationSeconds: Double,
            distanceMeters: Double,
            queriedAtEpochMillis: Long,
            dataVersion: String? = null,
        ): RouteEstimateResult {
            if (points.size !in 2..MAX_ROUTE_POINTS || queriedAtEpochMillis < 0) {
                return RouteEstimateResult.Failed(RouteEstimateFailure.INVALID_REQUEST, "출발지와 방문할 장소를 다시 확인해주세요")
            }
            if (observedLegs.size != points.size - 1 || snappedPoints.size != points.size) return invalidResponse()
            if (!validMetric(durationSeconds) || !validMetric(distanceMeters) ||
                observedLegs.any { !validMetric(it.durationSeconds) || !validMetric(it.distanceMeters) }) return invalidResponse()
            if (dataVersion != null && (dataVersion.isBlank() || dataVersion.length > 100 || dataVersion.any { it.isISOControl() })) {
                return invalidResponse()
            }
            for ((index, observed) in snappedPoints.withIndex()) {
                if (observed.latitude !in -90.0..90.0 || observed.longitude !in -180.0..180.0 ||
                    !validMetric(observed.distanceMeters)) return invalidResponse()
                if (observed.distanceMeters > MAX_SNAP_METERS || distanceFrom(points[index], observed) > MAX_SNAP_METERS) {
                    return RouteEstimateResult.Failed(RouteEstimateFailure.SNAP_TOO_FAR, "선택한 장소와 연결된 도로가 너무 멀어요. 출입구 위치를 확인해주세요")
                }
            }
            val legDuration = observedLegs.sumOf { it.durationSeconds }
            val legDistance = observedLegs.sumOf { it.distanceMeters }
            // OSRM rounds route and leg values independently to one decimal place.
            val tolerance = points.size * 0.11
            if (!legDuration.isFinite() || !legDistance.isFinite() ||
                kotlin.math.abs(legDuration - durationSeconds) > tolerance ||
                kotlin.math.abs(legDistance - distanceMeters) > tolerance) return invalidResponse()
            val copiedPoints = points.toList()
            val legs = observedLegs.mapIndexed { index, observed ->
                RouteEstimateLeg(copiedPoints[index], copiedPoints[index + 1], observed.durationSeconds, observed.distanceMeters)
            }
            return RouteEstimateResult.Success(RouteEstimate(copiedPoints, legs, snappedPoints.toList(), queriedAtEpochMillis, dataVersion))
        }

        private fun validMetric(value: Double): Boolean = value.isFinite() && value >= 0

        private fun invalidResponse(): RouteEstimateResult.Failed =
            RouteEstimateResult.Failed(RouteEstimateFailure.INVALID_RESPONSE, "경로 응답의 시간·거리·방문 순서를 확인하지 못했어요")

        private fun distanceFrom(point: NavigationPoint, observed: SnappedRoutePoint): Double {
            val latitudeDelta = Math.toRadians(observed.latitude - point.latitude)
            val longitudeDelta = Math.toRadians(observed.longitude - point.longitude)
            val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) + cos(Math.toRadians(point.latitude)) *
                cos(Math.toRadians(observed.latitude)) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
            return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}

internal enum class RouteEstimateFailure {
    INVALID_REQUEST, NETWORK, TIMEOUT, HTTP, NO_ROUTE, NO_SEGMENT, INVALID_RESPONSE, SNAP_TOO_FAR,
}

internal sealed interface RouteEstimateResult {
    data class Success(val estimate: RouteEstimate) : RouteEstimateResult
    data class Failed(val kind: RouteEstimateFailure, val reason: String) : RouteEstimateResult
}
