package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.routing.ObservedRouteLeg
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateFailure
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.routing.RouteEstimateResult
import io.moduhome.app.core.routing.SnappedRoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutedJourneyPlanningTest {
    private val now = 1_800_000_000_000L
    private val origin = NavigationPoint("출발지", 37.54, 127.0)
    private val home = NavigationPoint("집", 37.55, 127.01, placeId = "home")
    private val pickup = NavigationPoint("픽업", 37.56, 127.02, placeId = "pickup")
    private val groceries = NavigationPoint("마트", 37.57, 127.03, placeId = "groceries")
    private val draft = JourneyDraft(origin = origin.name, home = home.name, budget = 100,
        pickup = VisitDraft(place = pickup.name, stay = "0", ready = true),
        groceries = VisitDraft(place = groceries.name, stay = "0"),
        navigationPlaces = NavigationPlaces(home, pickup, groceries))

    private fun estimate(
        points: List<NavigationPoint>,
        seconds: List<Double>,
        version: String? = "2026-10-20T00:00:00Z",
        queriedAt: Long = now,
    ): RouteEstimate {
        val legs = seconds.map { ObservedRouteLeg(it, it * 10) }
        return (RouteEstimate.fromObservation(points, legs,
            points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
            seconds.sum(), legs.sumOf { it.distanceMeters }, queriedAt, version) as RouteEstimateResult.Success).estimate
    }

    private val direct get() = estimate(listOf(origin, home), listOf(600.0))
    private val pickupFirst get() = estimate(listOf(origin, pickup, groceries, home), listOf(61.0, 121.0, 180.0))
    private val groceriesFirst get() = estimate(listOf(origin, groceries, pickup, home), listOf(240.0, 300.0, 360.0))
    private val routes get() = linkedMapOf("PICKUP>GROCERIES" to pickupFirst, "GROCERIES>PICKUP" to groceriesFirst)
    private val routed get() = checkNotNull(RoutedJourneyEstimates.create(draft, origin, direct, routes))

    @Test fun routedPlansUseTheirOwnLegsAndRoundEachLegUpToMinutes() {
        val state = HomeUiState(draft, routing = routed)
        val pickupPlan = state.plans.single { it.id == "PICKUP>GROCERIES" }
        val groceriesPlan = state.plans.single { it.id == "GROCERIES>PICKUP" }
        assertEquals(listOf(2, 3, 3), routed.routedTimes.routes.getValue("PICKUP>GROCERIES"))
        assertEquals(8, pickupPlan.drivingMinutes)
        assertEquals(15, groceriesPlan.drivingMinutes)
        assertEquals(10, state.directHomeMinutes)
        assertTrue(routed.roundingPolicy.contains("구간별"))
        assertEquals(0, pickupPlan.additionalMinutes)
        assertEquals(5, groceriesPlan.additionalMinutes)
    }

    @Test fun manualTravelFieldErrorsDoNotBlockAnOtherwiseValidRoutingRequestOrRoutedPlan() {
        val malformed = draft.copy(travel = TravelLeg.entries.associateWith { "not a number" })
        assertNull(malformed.validate().request)
        assertNotNull(malformed.validate(requireTravelTimes = false).request)
        val state = HomeUiState(malformed, routing = routed)
        assertNotNull(state.validation.request)
        assertEquals(2, state.plans.size)
        assertEquals(10, state.directHomeMinutes)
    }

    @Test fun routingValidationStillRequiresRealVisitConditions() {
        val invalid = draft.copy(pickup = draft.pickup.copy(stay = "invalid", deadline = "99:00"))
        val errors = invalid.validate(requireTravelTimes = false).errors
        assertTrue(errors.containsKey("pickup.stay"))
        assertTrue(errors.containsKey("pickup.deadline"))
        assertTrue(HomeUiState(invalid, routing = routed).plans.isEmpty())
    }

    @Test fun bothCandidateRoutesAreRequiredBeforePublishingAComparison() {
        assertNull(RoutedJourneyEstimates.create(draft, origin, direct, mapOf("PICKUP>GROCERIES" to pickupFirst)))
        assertNull(RoutedJourneyEstimates.create(draft, origin, direct, routes + ("PICKUP" to pickupFirst)))
        assertNull(RoutedJourneyEstimates.create(draft, origin, direct, routes.mapValues { groceriesFirst }))
    }

    @Test fun changingAPlaceOrVisitSelectionCannotSilentlyFallBackToManualTimes() {
        val changed = draft.copy(home = "다른 집")
        assertFalse(routed.matches(changed))
        val state = HomeUiState(changed, routing = routed)
        assertTrue(state.plans.isEmpty())
        assertNull(state.directHomeMinutes)
        assertTrue(state.validation.errors.containsKey("routing"))
        assertFalse(routed.matches(draft.copy(groceries = draft.groceries.copy(enabled = false))))
        assertFalse(routed.matches(draft.copy(origin = "새 출발지")))
    }

    @Test fun aDifferentPoiWithTheSameNameAndCoordinateRequiresAnewQuery() {
        val changed = draft.copy(navigationPlaces = draft.navigationPlaces.copy(pickup = pickup.copy(placeId = "other")))
        assertFalse(routed.matches(changed))
    }

    @Test fun stayBudgetAndDepartureEditsReuseRoadEstimatesWhileRecalculatingThePlan() {
        val changed = draft.copy(departure = "19:00", budget = 20, pickup = draft.pickup.copy(stay = "5"))
        assertTrue(routed.matches(changed))
        val selected = HomeUiState(changed, routing = routed).plans.single { it.id == "PICKUP>GROCERIES" }
        assertEquals(8, selected.drivingMinutes)
        assertEquals(5, selected.stayMinutes)
        assertEquals(19 * 60 + 13, selected.homeArrivalMinute)
    }

    @Test fun oneEnabledVisitRequiresOnlyItsOwnCompleteRoute() {
        val single = draft.copy(groceries = draft.groceries.copy(enabled = false),
            navigationPlaces = draft.navigationPlaces.copy(groceries = null))
        val route = estimate(listOf(origin, pickup, home), listOf(120.0, 360.0))
        val snapshot = RoutedJourneyEstimates.create(single, origin, direct, mapOf("PICKUP" to route))
        assertNotNull(snapshot)
        assertEquals(1, HomeUiState(single, routing = snapshot).plans.size)
    }

    @Test fun expiredEstimatesRemainARecordOfAnActiveOrCompletedJourney() {
        val nowLater = now + RouteEstimate.FRESHNESS_MILLIS + 1
        assertFalse(routed.isFresh(nowLater))
        listOf(JourneyStage.ACTIVE, JourneyStage.COMPLETE).forEach { stage ->
            val state = HomeUiState(draft, JourneyProgress(stage, "PICKUP>GROCERIES"), routing = routed)
            assertEquals(8, state.selectedPlan?.drivingMinutes)
        }
        assertTrue(routed.isFresh(now + RouteEstimate.FRESHNESS_MILLIS))
    }

    @Test fun mixedMapVersionsOrExcessivelySeparatedQueriesAreRejected() {
        val otherVersion = estimate(groceriesFirst.points, listOf(240.0, 300.0, 360.0), version = "2026-10-21T00:00:00Z")
        assertNull(RoutedJourneyEstimates.create(draft, origin, direct, routes + ("GROCERIES>PICKUP" to otherVersion)))
        val late = estimate(groceriesFirst.points, listOf(240.0, 300.0, 360.0), queriedAt = now + RouteEstimate.FRESHNESS_MILLIS + 1)
        assertNull(RoutedJourneyEstimates.create(draft, origin, direct, routes + ("GROCERIES>PICKUP" to late)))
    }

    @Test fun aLegBeyondTheSupportedPlanningRangeIsNotClampedToAnAllowedValue() {
        val tooLong = estimate(pickupFirst.points, listOf(480 * 60.0 + 0.1, 121.0, 180.0))
        assertNull(RoutedJourneyEstimates.create(draft, origin, direct, routes + ("PICKUP>GROCERIES" to tooLong)))
    }

    @Test fun allQueriesKeepTheCompleteVisitOrderAndOnlyThenReturnSuccess() = runBlocking {
        val calls = mutableListOf<List<NavigationPoint>>()
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult {
                calls += points
                val observed = if (points.size == 2) direct else if (points[1] == pickup) pickupFirst else groceriesFirst
                return RouteEstimateResult.Success(observed)
            }
        }
        val result = loadRoutedJourney(draft.copy(travel = emptyMap()), origin, gateway)
        assertTrue(result is RoutedJourneyLoadResult.Success)
        assertEquals(listOf(listOf(origin, home), listOf(origin, pickup, groceries, home),
            listOf(origin, groceries, pickup, home)), calls)
    }

    @Test fun oneFailedCandidateInvalidatesTheWholeLoadInsteadOfReturningPartialTimes() = runBlocking {
        var calls = 0
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult {
                calls += 1
                return if (calls == 1) RouteEstimateResult.Success(direct)
                else RouteEstimateResult.Failed(RouteEstimateFailure.NO_ROUTE, "도로 경로가 없어요")
            }
        }
        assertTrue(loadRoutedJourney(draft, origin, gateway) is RoutedJourneyLoadResult.Failed)
        assertEquals(2, calls)
    }

    @Test fun missingNavigationPointsFailBeforeMakingAnyNetworkRequests() = runBlocking {
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult = error("must not query")
        }
        assertTrue(loadRoutedJourney(draft.copy(navigationPlaces = NavigationPlaces()), origin, gateway)
            is RoutedJourneyLoadResult.Failed)
    }

    @Test fun callerCancellationIsPropagatedInsteadOfBecomingAnErrorOrPartialSnapshot() {
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult = throw CancellationException("cancelled")
        }
        assertThrows(CancellationException::class.java) { runBlocking { loadRoutedJourney(draft, origin, gateway) } }
    }
}
