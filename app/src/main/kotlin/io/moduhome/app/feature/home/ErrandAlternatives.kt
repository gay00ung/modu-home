package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.ErrandJourneyPlan
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.routing.RouteEstimateResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.ZoneId

/** An option belongs to one completed lookup; old buttons cannot select a newer option. */
internal class ErrandAlternative(
    val kind: VisitKind,
    val draft: JourneyDraft,
    val routing: RoutedJourneyEstimates,
    val plan: ErrandJourneyPlan,
)

/** Transient until the user chooses a scope. Every scope shares the same direct-home observation. */
internal class ErrandAlternativeComparison private constructor(
    val draft: JourneyDraft,
    val full: RoutedJourneyEstimates,
    val options: List<ErrandAlternative>,
) {
    fun matches(draft: JourneyDraft, routing: RoutedJourneyEstimates?): Boolean =
        this.draft == draft && full === routing && full.matches(draft)

    fun isFresh(nowMillis: Long): Boolean = full.isFresh(nowMillis) && options.all { it.routing.isFresh(nowMillis) }

    fun rebaseDeparture(nowMillis: Long, zoneId: ZoneId): ErrandAlternativeComparison? {
        val current = when (val result = DepartureDraftResult.prepare(draft, nowMillis, zoneId)) {
            is DepartureDraftResult.Ready -> result.draft
            is DepartureDraftResult.CalendarReview -> return null
            is DepartureDraftResult.Invalid -> return null
        }
        val routes = full.routes + options.flatMap { it.routing.routes.entries }.associate { it.toPair() }
        return create(current, full.origin, full.direct, routes)
    }

    companion object {
        fun create(
            draft: JourneyDraft,
            origin: NavigationPoint,
            direct: RouteEstimate,
            routes: Map<String, RouteEstimate>,
        ): ErrandAlternativeComparison? {
            if (!draft.pickup.enabled || !draft.groceries.enabled || draft.validate(false).request == null) return null
            val all = listOf(direct) + routes.values
            if (all.map { it.dataVersion }.distinct().size != 1 ||
                all.maxOf { it.queriedAtEpochMillis } - all.minOf { it.queriedAtEpochMillis } > RouteEstimate.FRESHNESS_MILLIS) return null
            val fullOrders = routeOrders(draft, origin) ?: return null
            val optionDrafts = VisitKind.entries.map { kind -> kind to draft.copy(
                pickup = draft.pickup.copy(enabled = kind == VisitKind.PICKUP),
                groceries = draft.groceries.copy(enabled = kind == VisitKind.GROCERIES),
            ) }
            val expectedKeys = fullOrders.keys + optionDrafts.flatMap { (_, input) ->
                routeOrders(input, origin)?.keys ?: return null
            }
            if (routes.keys != expectedKeys.toSet()) return null
            val full = RoutedJourneyEstimates.create(draft, origin, direct, routes.filterKeys { it in fullOrders }) ?: return null
            val options = optionDrafts.map { (kind, input) ->
                val orders = routeOrders(input, origin) ?: return null
                val routing = RoutedJourneyEstimates.create(input, origin, direct, routes.filterKeys { it in orders }) ?: return null
                val plan = HomeUiState(input, routing = routing).plans.singleOrNull() ?: return null
                ErrandAlternative(kind, input, routing, plan)
            }
            return ErrandAlternativeComparison(draft, full, options)
        }
    }
}

internal sealed interface ErrandAlternativesLoadResult {
    data class Success(val comparison: ErrandAlternativeComparison) : ErrandAlternativesLoadResult
    data class Failed(val reason: String) : ErrandAlternativesLoadResult
}

/** Requests full routes for each scope, never splices legs after removing a stop. */
internal suspend fun loadErrandAlternatives(
    draft: JourneyDraft,
    origin: NavigationPoint,
    gateway: RouteEstimateGateway,
): ErrandAlternativesLoadResult {
    if (!draft.pickup.enabled || !draft.groceries.enabled || draft.validate(false).request == null) {
        return ErrandAlternativesLoadResult.Failed("픽업과 장보기를 함께 비교한 뒤 대안을 확인해주세요")
    }
    val home = draft.navigationPoint(NavigationPlaceRole.HOME)
        ?: return ErrandAlternativesLoadResult.Failed("집의 내비 장소를 선택해주세요")
    val fullOrders = routeOrders(draft, origin)
        ?: return ErrandAlternativesLoadResult.Failed("들를 곳의 내비 장소를 모두 선택해주세요")
    val orders = fullOrders.toMutableMap()
    for (kind in VisitKind.entries) {
        val single = draft.copy(pickup = draft.pickup.copy(enabled = kind == VisitKind.PICKUP),
            groceries = draft.groceries.copy(enabled = kind == VisitKind.GROCERIES))
        orders.putAll(routeOrders(single, origin) ?: return ErrandAlternativesLoadResult.Failed("볼일 장소를 확인해주세요"))
    }
    currentCoroutineContext().ensureActive()
    val direct = when (val result = gateway.estimate(listOf(origin, home))) {
        is RouteEstimateResult.Success -> result.estimate
        is RouteEstimateResult.Failed -> return ErrandAlternativesLoadResult.Failed("집 직행 경로: ${result.reason}")
    }
    val routes = linkedMapOf<String, RouteEstimate>()
    for ((id, points) in orders) {
        currentCoroutineContext().ensureActive()
        when (val result = gateway.estimate(points)) {
            is RouteEstimateResult.Success -> routes[id] = result.estimate
            is RouteEstimateResult.Failed -> return ErrandAlternativesLoadResult.Failed(result.reason)
        }
    }
    currentCoroutineContext().ensureActive()
    val comparison = ErrandAlternativeComparison.create(draft, origin, direct, routes)
        ?: return ErrandAlternativesLoadResult.Failed("모든 대안을 같은 도로 데이터로 확인하지 못했어요. 다시 비교해주세요")
    return ErrandAlternativesLoadResult.Success(comparison)
}

