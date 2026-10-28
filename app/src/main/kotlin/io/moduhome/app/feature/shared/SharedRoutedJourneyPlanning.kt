package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateFailure
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.routing.RouteEstimateResult
import io.moduhome.app.core.shared.SharedDrivingEstimate
import io.moduhome.app.core.shared.SharedJourneyOrder
import io.moduhome.app.core.shared.SharedJourneyPlanner
import io.moduhome.app.core.shared.SharedJourneyRequest
import io.moduhome.app.core.shared.SharedOrderTravelEstimate
import io.moduhome.app.core.shared.SharedOrderTravelTimes
import io.moduhome.app.core.shared.SharedOrderedDrivingLeg
import io.moduhome.app.core.shared.TravelEstimateKind
import io.moduhome.app.core.shared.TravelEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.ZoneId
import kotlin.math.ceil

/** Only the listed legs are driven before the next stop confirmation changes the guidance window. */
internal data class SharedGuidanceWindowEstimate(val route: RouteEstimate, val consumedLegIndices: List<Int>)

internal class SharedRoutedJourneyEstimates private constructor(
    private val draft: SharedJourneyDraft,
    val origin: NavigationPoint,
    val departureAtMillis: Long,
    val request: SharedJourneyRequest,
    val direct: RouteEstimate,
    val windows: Map<String, List<SharedGuidanceWindowEstimate>>,
    val travel: SharedOrderTravelTimes,
    val validatedAtMillis: Long,
    val zoneId: ZoneId,
) {
    private val responses = listOf(direct) + windows.values.flatten().map { it.route }
    val plans = SharedJourneyPlanner().compare(request, travel)
    val source: String get() = "OSM 도로망 예상 · 실시간 교통 미반영"
    val dataVersion: String get() = requireNotNull(direct.dataVersion)
    val queriedAtEpochMillis: Long get() = responses.minOf { it.queriedAtEpochMillis }
    val directHomeMinutes: Int get() = travel.directHome.estimate.minutes
    val includesLiveTraffic: Boolean get() = false

    fun matches(draft: SharedJourneyDraft): Boolean = this.draft == draft
    fun isFresh(nowEpochMillis: Long): Boolean = responses.all { it.isFresh(nowEpochMillis) }

    /** Recalculates departure and deadlines; original road responses retain their query times. */
    fun rebaseDeparture(nowMillis: Long): SharedRoutedJourneyEstimates? {
        if (nowMillis < validatedAtMillis || !isFresh(nowMillis)) return null
        return restore(draft, origin, nowMillis, direct, windows, nowMillis, zoneId)
    }

    companion object {
        /** Rebuilds an old observation at its original validation time; it never refreshes it. */
        fun restore(
            draft: SharedJourneyDraft,
            origin: NavigationPoint,
            departureAtMillis: Long,
            direct: RouteEstimate,
            windows: Map<String, List<SharedGuidanceWindowEstimate>>,
            validatedAtMillis: Long,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): SharedRoutedJourneyEstimates? {
            if (departureAtMillis < 0 || validatedAtMillis < departureAtMillis) return null
            val input = draft.copy(passengers = draft.passengers.toList())
            val request = input.validate(origin, departureAtMillis, zoneId).request ?: return null
            if (direct.points != listOf(origin, request.home.point)) return null
            val orders = SharedJourneyPlanner().orders(request)
            if (windows.keys != orders.map { it.id }.toSet()) return null
            val observations = linkedMapOf<String, List<SharedGuidanceWindowEstimate>>()
            val estimates = mutableListOf<SharedOrderTravelEstimate>()
            for (order in orders) {
                val saved = windows[order.id] ?: return null
                val expected = guidanceWindowRequests(order)
                if (saved.size != expected.size) return null
                if (saved.withIndex().any { (index, window) ->
                        window.route.points != expected[index].first || window.consumedLegIndices != expected[index].second
                    }) return null
                val consumed = saved.flatMap { window -> window.consumedLegIndices.map { index ->
                    window.route.legs[index] to window.route
                } }
                if (consumed.size != order.places.size - 1) return null
                val legs = consumed.mapIndexed { index, (leg, response) ->
                    if (leg.from != order.places[index].point || leg.to != order.places[index + 1].point) return null
                    val minutes = sharedMinutes(leg.durationSeconds) ?: return null
                    SharedOrderedDrivingLeg(order.places[index], order.places[index + 1],
                        SharedDrivingEstimate(minutes, response.sharedEvidence()))
                }
                estimates += SharedOrderTravelEstimate(order, legs)
                observations[order.id] = saved.map { it.copy(consumedLegIndices = it.consumedLegIndices.toList()) }
            }
            val all = listOf(direct) + observations.values.flatten().map { it.route }
            if (all.any { !it.isFresh(validatedAtMillis) || it.dataVersion == null } ||
                all.map { it.dataVersion }.distinct().size != 1) return null
            val directMinutes = sharedMinutes(direct.durationSeconds) ?: return null
            val travel = SharedOrderTravelTimes.create(request,
                SharedOrderedDrivingLeg(request.origin, request.home, SharedDrivingEstimate(directMinutes, direct.sharedEvidence())), estimates)
                ?: return null
            return SharedRoutedJourneyEstimates(input, origin, departureAtMillis, request, direct,
                observations.toMap(), travel, validatedAtMillis, zoneId)
        }
    }
}

internal sealed interface SharedRoutedJourneyLoadResult {
    data class Success(val estimates: SharedRoutedJourneyEstimates) : SharedRoutedJourneyLoadResult
    data class Failed(val reason: String, val errors: Map<String, String> = emptyMap()) : SharedRoutedJourneyLoadResult
}

/**
 * Loads every stop order atomically. A three-stop order uses the first leg of its initial
 * two-waypoint window, then all legs of its next window. This estimates the staged guidance;
 * it does not send navigation commands or claim that all three stops were already applied.
 */
internal suspend fun loadSharedRoutedJourney(
    draft: SharedJourneyDraft,
    origin: NavigationPoint,
    gateway: RouteEstimateGateway,
    clock: () -> Long = System::currentTimeMillis,
    zoneId: ZoneId = ZoneId.systemDefault(),
): SharedRoutedJourneyLoadResult {
    val input = draft.copy(passengers = draft.passengers.toList())
    val departureAtMillis = clock()
    val validation = input.validate(origin, departureAtMillis, zoneId)
    val request = validation.request ?: return SharedRoutedJourneyLoadResult.Failed(
        validation.errors.values.firstOrNull() ?: "동승자의 실제 하차 장소와 조건을 확인해주세요", validation.errors)
    try {
        val direct = when (val result = querySharedRoute(listOf(origin, request.home.point), gateway)) {
            is RouteEstimateResult.Success -> result.estimate
            is RouteEstimateResult.Failed -> return SharedRoutedJourneyLoadResult.Failed("집 직행 경로: ${result.reason}")
        }
        val observations = linkedMapOf<String, List<SharedGuidanceWindowEstimate>>()
        for (order in SharedJourneyPlanner().orders(request)) {
            val routeWindows = mutableListOf<SharedGuidanceWindowEstimate>()
            for ((points, consumed) in guidanceWindowRequests(order)) {
                when (val result = querySharedRoute(points, gateway)) {
                    is RouteEstimateResult.Success -> routeWindows += SharedGuidanceWindowEstimate(result.estimate, consumed)
                    is RouteEstimateResult.Failed -> return SharedRoutedJourneyLoadResult.Failed("방문 순서 경로: ${result.reason}")
                }
            }
            observations[order.id] = routeWindows.toList()
        }
        val all = listOf(direct) + observations.values.flatten().map { it.route }
        val now = clock()
        if (now < departureAtMillis || all.any { !it.isFresh(now) } ||
            all.maxOf { it.queriedAtEpochMillis } - all.minOf { it.queriedAtEpochMillis } > RouteEstimate.FRESHNESS_MILLIS) {
            return SharedRoutedJourneyLoadResult.Failed("경로를 확인하는 동안 시간이 지났어요. 다시 비교해주세요")
        }
        if (all.any { it.dataVersion == null } || all.map { it.dataVersion }.distinct().size != 1) {
            return SharedRoutedJourneyLoadResult.Failed("도로 데이터 기준을 동일하게 확인하지 못했어요. 다시 비교해주세요")
        }
        val restored = SharedRoutedJourneyEstimates.restore(input, origin, departureAtMillis,
            direct, observations, now, zoneId) ?: return invalidSharedResponse()
        currentCoroutineContext().ensureActive()
        return SharedRoutedJourneyLoadResult.Success(restored)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return SharedRoutedJourneyLoadResult.Failed("동승자와 볼일의 도로 경로를 확인하지 못했어요. 다시 시도해주세요")
    }
}

private fun guidanceWindowRequests(order: SharedJourneyOrder): List<Pair<List<NavigationPoint>, List<Int>>> {
    val points = order.places.map { it.point }
    return if (order.stopIds.size == 3) listOf(
        listOf(points[0], points[1], points[2], points.last()) to listOf(0),
        points.drop(1) to listOf(0, 1, 2),
    ) else listOf(points to points.drop(1).indices.toList())
}

private suspend fun querySharedRoute(points: List<NavigationPoint>, gateway: RouteEstimateGateway): RouteEstimateResult {
    currentCoroutineContext().ensureActive()
    val result = gateway.estimate(points)
    currentCoroutineContext().ensureActive()
    return when (result) {
        is RouteEstimateResult.Failed -> result
        is RouteEstimateResult.Success -> if (result.estimate.points == points) result else
            RouteEstimateResult.Failed(RouteEstimateFailure.INVALID_RESPONSE, "요청한 출발지와 방문 순서가 응답과 달라요")
    }
}

private fun sharedMinutes(seconds: Double): Int? = ceil(seconds / 60.0)
    .takeIf { it in 0.0..480.0 }?.toInt()

private fun RouteEstimate.sharedEvidence(): TravelEvidence = TravelEvidence(
    TravelEstimateKind.ROUTING_SERVICE, source, queriedAtEpochMillis, includesLiveTraffic = false,
)

private fun invalidSharedResponse() = SharedRoutedJourneyLoadResult.Failed(
    "모든 방문 순서의 구간시간을 확인하지 못했어요. 다시 비교해주세요",
)
