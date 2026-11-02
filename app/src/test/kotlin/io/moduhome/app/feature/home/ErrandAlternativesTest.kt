package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.*
import io.moduhome.app.data.JourneyJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ErrandAlternativesTest {
    private val now = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private val origin = NavigationPoint("현재 차량 위치", 37.4127, 127.0946)
    private val home = NavigationPoint("집", 37.416, 127.098)
    private val pickup = NavigationPoint("픽업", 37.413, 127.095)
    private val groceries = NavigationPoint("장보기", 37.412, 127.093)
    private val draft = JourneyDraft(origin = origin.name, home = home.name, departure = "09:00",
        dateEpochDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay(), clockZoneId = zone.id,
        budget = 20, pickup = VisitDraft(place = pickup.name, stay = "15", ready = true),
        groceries = VisitDraft(place = groceries.name, stay = "15"),
        navigationPlaces = NavigationPlaces(home, pickup, groceries))

    private fun response(points: List<NavigationPoint>, version: String = "roads-1", at: Long = now): RouteEstimate {
        val seconds = when {
            points.size == 2 -> listOf(300.0)
            points.size == 4 -> listOf(180.0, 180.0, 180.0)
            points[1] == pickup -> listOf(60.0, 60.0)
            else -> listOf(240.0, 120.0)
        }
        return (RouteEstimate.fromObservation(points, seconds.map { ObservedRouteLeg(it, it * 10) },
            points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
            seconds.sum(), seconds.sum() * 10, at, version) as RouteEstimateResult.Success).estimate
    }

    private suspend fun compared(input: JourneyDraft = draft): ErrandAlternativeComparison {
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>) = RouteEstimateResult.Success(response(points))
        }
        return (loadErrandAlternatives(input, origin, gateway) as ErrandAlternativesLoadResult.Success).comparison
    }

    private fun state(comparison: ErrandAlternativeComparison): HomeUiState {
        val input = HomeUiState(comparison.draft, routing = comparison.full, alternatives = comparison)
        return input.copy(progress = input.progress.compare(input.plans))
    }

    @Test fun everyScopeGetsItsOwnRouteAndTheDirectHomeObservationIsShared() = runTest {
        val calls = mutableListOf<List<NavigationPoint>>()
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult {
                calls += points
                return RouteEstimateResult.Success(response(points))
            }
        }
        val result = (loadErrandAlternatives(draft, origin, gateway) as ErrandAlternativesLoadResult.Success).comparison
        assertEquals(listOf(listOf(origin, home), listOf(origin, pickup, groceries, home),
            listOf(origin, groceries, pickup, home), listOf(origin, pickup, home), listOf(origin, groceries, home)), calls)
        assertEquals(listOf(12, 16), result.options.map { it.plan.additionalMinutes })
        assertTrue(result.options.all { it.plan.isValid })
        assertTrue(HomeUiState(draft, routing = result.full).plans.none { it.isValid })
        result.options.forEach { assertSame(result.full.direct, it.routing.direct) }
        assertEquals(2, result.options.first().plan.drivingMinutes)
    }

    @Test fun selectionKeepsTheBudgetAndOtherInputsThenRequiresExplicitDeparture() = runTest {
        val compared = compared()
        val current = state(compared)
        for (option in compared.options) {
            val next = requireNotNull(current.withErrandAlternative(option, now, zone))
            assertEquals(20, next.draft.budget)
            assertEquals(draft.pickup.copy(enabled = option.kind == VisitKind.PICKUP), next.draft.pickup)
            assertEquals(draft.groceries.copy(enabled = option.kind == VisitKind.GROCERIES), next.draft.groceries)
            assertEquals(draft.navigationPlaces, next.draft.navigationPlaces)
            assertEquals(current.acceptedProposals, next.acceptedProposals)
            assertEquals(JourneyStage.REVIEW, next.progress.stage)
            assertNull(next.progress.startedAtMillis)
            assertEquals(listOf(option.kind), next.selectedPlan!!.visits.map { it.visit.kind })
            assertNull(next.alternatives)
            val restored = JourneyJson.decode(requireNotNull(JourneyJson.encode(next)), draft, now)
            assertEquals(next.draft, restored.draft)
            assertEquals(next.progress, restored.progress)
        }
    }

    @Test fun notReadyPickupIsNotSelectedButGroceriesCanStillBeChosen() = runTest {
        val result = compared(draft.copy(pickup = draft.pickup.copy(ready = false)))
        val current = state(result)
        assertNull(current.withErrandAlternative(result.options.first { it.kind == VisitKind.PICKUP }, now, zone))
        assertNotNull(current.withErrandAlternative(result.options.first { it.kind == VisitKind.GROCERIES }, now, zone))
    }

    @Test fun alternativesThatStillExceedTheUnchangedBudgetRemainInvalid() = runTest {
        val result = compared(draft.copy(budget = 10))
        assertTrue(result.options.all { !it.plan.isValid && it.draft.budget == 10 })
        result.options.forEach { assertNull(state(result).withErrandAlternative(it, now, zone)) }
    }

    @Test fun activeDeadlineAndWalkingTimeAreRetained() = runTest {
        val input = draft.copy(pickup = draft.pickup.copy(walking = "2", deadline = "09:10"))
        val result = compared(input)
        val option = result.options.first { it.kind == VisitKind.PICKUP }
        assertFalse(option.plan.isValid)
        assertEquals(input.pickup, option.draft.pickup)
        assertNull(state(result).withErrandAlternative(option, now, zone))
    }

    @Test fun changedDraftAndStaleOrReplacedOptionsCannotBeApplied() = runTest {
        val result = compared()
        val current = state(result)
        val option = result.options.first()
        assertNull(current.copy(draft = draft.copy(budget = 30)).withErrandAlternative(option, now, zone))
        assertNull(current.withErrandAlternative(compared().options.first(), now, zone))
        assertNull(current.withErrandAlternative(option, now + RouteEstimate.FRESHNESS_MILLIS + 1, zone))
        for (stage in JourneyStage.entries.filter { it != JourneyStage.COMPARE }) {
            assertNull(current.copy(progress = current.progress.copy(stage = stage)).withErrandAlternative(option, now, zone))
        }
    }

    @Test fun reopeningDropsUnacceptedOptionsAndKeepsTheFullOriginalPlan() = runTest {
        val current = state(compared())
        val restored = JourneyJson.decode(requireNotNull(JourneyJson.encode(current)), draft, now)
        assertNull(restored.alternatives)
        assertTrue(restored.draft.pickup.enabled && restored.draft.groceries.enabled)
        assertEquals(20, restored.draft.budget)
    }

    @Test fun selectionRechecksCurrentTimeWithoutPretendingToRefreshRoadResponses() = runTest {
        val result = compared(draft.copy(pickup = draft.pickup.copy(deadline = "09:16")))
        val option = result.options.first { it.kind == VisitKind.PICKUP }
        assertTrue(option.plan.isValid)
        assertNull(state(result).withErrandAlternative(option, now + 60_000, zone))
        assertEquals(now, option.routing.queriedAtEpochMillis)
    }

    @Test fun mixedMapVersionsDoNotProduceAPartialComparison() = runTest {
        var count = 0
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult {
                count += 1
                return RouteEstimateResult.Success(response(points, if (count == 5) "roads-2" else "roads-1"))
            }
        }
        assertTrue(loadErrandAlternatives(draft, origin, gateway) is ErrandAlternativesLoadResult.Failed)
    }

    @Test fun failureAndCancellationDoNotTurnIntoACompletedComparison() = runTest {
        var calls = 0
        val failed = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult {
                calls += 1
                return if (calls == 4) RouteEstimateResult.Failed(RouteEstimateFailure.NETWORK, "연결 실패")
                else RouteEstimateResult.Success(response(points))
            }
        }
        assertTrue(loadErrandAlternatives(draft, origin, failed) is ErrandAlternativesLoadResult.Failed)
        assertEquals(4, calls)
        val cancelled = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult = throw CancellationException("화면 이탈")
        }
        assertTrue(runCatching { loadErrandAlternatives(draft, origin, cancelled) }.exceptionOrNull() is CancellationException)
    }
}
