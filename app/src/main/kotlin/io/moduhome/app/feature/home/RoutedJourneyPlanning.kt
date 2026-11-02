package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.RoutedJourneyTimes
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.routing.RouteEstimateResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

/** All candidate responses must be present and tied to their own requested visit order. */
internal class RoutedJourneyEstimates private constructor(
    val originLabel: String,
    val origin: NavigationPoint,
    val direct: RouteEstimate,
    val routes: Map<String, RouteEstimate>,
    val routedTimes: RoutedJourneyTimes,
) {
    val directHomeMinutes: Int get() = routedTimes.directHomeMinutes
    val source: String get() = direct.source
    val dataVersion: String? get() = direct.dataVersion
    val includesLiveTraffic: Boolean get() = false
    val queriedAtEpochMillis: Long get() = (listOf(direct) + routes.values).minOf { it.queriedAtEpochMillis }
    val roundingPolicy: String get() = "이동시간은 구간별로 1분 단위 올림해 계산해요"

    fun matches(draft: JourneyDraft): Boolean {
        if (draft.origin.trim() != originLabel) return false
        val expected = routeOrders(draft, origin) ?: return false
        val home = draft.navigationPoint(NavigationPlaceRole.HOME) ?: return false
        return direct.points == listOf(origin, home) && routes.keys == expected.keys &&
            expected.all { (id, points) -> routes[id]?.points == points }
    }

    fun isFresh(nowEpochMillis: Long): Boolean =
        direct.isFresh(nowEpochMillis) && routes.values.all { it.isFresh(nowEpochMillis) }

    companion object {
        fun create(
            draft: JourneyDraft,
            origin: NavigationPoint,
            direct: RouteEstimate,
            routes: Map<String, RouteEstimate>,
        ): RoutedJourneyEstimates? {
            val orders = routeOrders(draft, origin) ?: return null
            val home = draft.navigationPoint(NavigationPlaceRole.HOME) ?: return null
            if (direct.points != listOf(origin, home) || routes.keys != orders.keys ||
                orders.any { (id, points) -> routes[id]?.points != points }) return null
            val all = listOf(direct) + routes.values
            if (all.map { it.dataVersion }.distinct().size != 1 ||
                all.maxOf { it.queriedAtEpochMillis } - all.minOf { it.queriedAtEpochMillis } > RouteEstimate.FRESHNESS_MILLIS) return null
            fun minutes(seconds: Double): Int? = ceil(seconds / 60.0)
                .takeIf { it in 0.0..RoutedJourneyTimes.MAX_LEG_MINUTES.toDouble() }?.toInt()
            val directMinutes = minutes(direct.durationSeconds) ?: return null
            val routeMinutes = linkedMapOf<String, List<Int>>()
            for ((id, route) in routes) {
                val converted = route.legs.map { minutes(it.durationSeconds) ?: return null }
                routeMinutes[id] = converted
            }
            val times = RoutedJourneyTimes(directMinutes, routeMinutes)
            return RoutedJourneyEstimates(draft.origin.trim(), origin, direct, routes.toMap(), times)
        }
    }
}

internal sealed interface RoutedJourneyLoadResult {
    data class Success(val estimates: RoutedJourneyEstimates) : RoutedJourneyLoadResult
    data class Failed(val reason: String) : RoutedJourneyLoadResult
}

/** Returns an atomic set; partial responses are never published as a completed comparison. */
internal suspend fun loadRoutedJourney(
    draft: JourneyDraft,
    origin: NavigationPoint,
    gateway: RouteEstimateGateway,
): RoutedJourneyLoadResult {
    val validation = draft.validate(requireTravelTimes = false)
    if (validation.request == null) return RoutedJourneyLoadResult.Failed(validation.errors.values.firstOrNull() ?: "입력한 조건을 확인해주세요")
    val home = draft.navigationPoint(NavigationPlaceRole.HOME)
        ?: return RoutedJourneyLoadResult.Failed("집의 내비 장소를 선택해주세요")
    val orders = routeOrders(draft, origin)
        ?: return RoutedJourneyLoadResult.Failed("들를 곳의 내비 장소를 모두 선택해주세요")
    val direct = when (val result = gateway.estimate(listOf(origin, home))) {
        is RouteEstimateResult.Success -> result.estimate
        is RouteEstimateResult.Failed -> return RoutedJourneyLoadResult.Failed("집 직행 경로: ${result.reason}")
    }
    val routes = linkedMapOf<String, RouteEstimate>()
    for ((id, points) in orders) {
        currentCoroutineContext().ensureActive()
        when (val result = gateway.estimate(points)) {
            is RouteEstimateResult.Success -> routes[id] = result.estimate
            is RouteEstimateResult.Failed -> return RoutedJourneyLoadResult.Failed(result.reason)
        }
    }
    currentCoroutineContext().ensureActive()
    val estimates = RoutedJourneyEstimates.create(draft, origin, direct, routes)
        ?: return RoutedJourneyLoadResult.Failed("모든 방문 순서의 경로를 같은 기준으로 확인하지 못했어요. 다시 조회해주세요")
    return RoutedJourneyLoadResult.Success(estimates)
}

internal fun routeOrders(draft: JourneyDraft, origin: NavigationPoint): Map<String, List<NavigationPoint>>? {
    val home = draft.navigationPoint(NavigationPlaceRole.HOME) ?: return null
    val visits = buildList {
        if (draft.pickup.enabled) add(VisitKind.PICKUP)
        if (draft.groceries.enabled) add(VisitKind.GROCERIES)
    }
    if (visits.isEmpty()) return null
    val orders = if (visits.size == 2) listOf(visits, visits.reversed()) else listOf(visits)
    return orders.associate { order ->
        val points = order.map { kind ->
            draft.navigationPoint(when (kind) {
                VisitKind.PICKUP -> NavigationPlaceRole.PICKUP
                VisitKind.GROCERIES -> NavigationPlaceRole.GROCERIES
            }) ?: return null
        }
        order.joinToString(">") { it.name } to (listOf(origin) + points + home)
    }
}
