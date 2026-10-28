package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.ObservedRouteLeg
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateFailure
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.routing.RouteEstimateResult
import io.moduhome.app.core.routing.SnappedRoutePoint
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.SharedJourneyPlanner
import io.moduhome.app.core.shared.SharedPlanIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SharedRoutedJourneyPlanningTest {
    private val now = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val origin = NavigationPoint("현재 차량", 37.50, 127.00)
    private val home = NavigationPoint("운전자 집", 37.51, 127.01)
    private val a = NavigationPoint("동승자 1 집", 37.52, 127.02)
    private val b = NavigationPoint("동승자 2 집", 37.53, 127.03)
    private val shop = NavigationPoint("픽업", 37.54, 127.04)
    private val checked = DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "사용자 확인", now - 500)
    private val first = SharedPassengerDraft("p1", "동승자 1", a, true, now - 1000, checked)
    private val second = SharedPassengerDraft("p2", "동승자 2", b, true, now - 1000, checked)
    private val draft = SharedJourneyDraft(home, listOf(first, second), SharedErrandDraft(VisitKind.PICKUP, shop, ready = true))

    private fun response(points: List<NavigationPoint>, seconds: List<Double> = List(points.size - 1) { 60.0 },
        version: String? = "test-map", at: Long = now): RouteEstimate {
        val legs = seconds.map { ObservedRouteLeg(it, it * 10.0) }
        return (RouteEstimate.fromObservation(points, legs,
            points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) }, seconds.sum(),
            legs.sumOf { it.distanceMeters }, at, version) as RouteEstimateResult.Success).estimate
    }

    private inner class Gateway(
        val answer: (List<NavigationPoint>, Int) -> RouteEstimateResult = { points, _ -> RouteEstimateResult.Success(response(points)) },
    ) : RouteEstimateGateway {
        val calls = mutableListOf<List<NavigationPoint>>()
        override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult {
            calls += points.toList()
            return answer(points, calls.lastIndex)
        }
    }

    private suspend fun load(input: SharedJourneyDraft = draft, gateway: RouteEstimateGateway = Gateway(),
        clock: () -> Long = { now }) = loadSharedRoutedJourney(input, origin, gateway, clock, zone)

    @Test fun threeStopsQueryTheTwoActualGuidanceWindowsForAllSixOrders() = runBlocking {
        val gateway = Gateway()
        val result = load(gateway = gateway) as SharedRoutedJourneyLoadResult.Success
        assertEquals(6, result.estimates.plans.size)
        assertEquals(13, gateway.calls.size)
        assertEquals(listOf(origin, home), gateway.calls.first())
        val orders = SharedJourneyPlanner().orders(result.estimates.request)
        orders.forEachIndexed { index, order ->
            val points = order.places.map { it.point }
            assertEquals(listOf(points[0], points[1], points[2], points.last()), gateway.calls[index * 2 + 1])
            assertEquals(points.drop(1), gateway.calls[index * 2 + 2])
            val windows = result.estimates.windows.getValue(order.id)
            assertEquals(listOf(0), windows[0].consumedLegIndices)
            assertEquals(listOf(0, 1, 2), windows[1].consumedLegIndices)
        }
        assertTrue(gateway.calls.all { it.last() == home && it.size <= 4 })
    }

    @Test fun unusedTailLegsOfTheInitialWindowNeverEnterTheJourneyTime() = runBlocking {
        val gateway = Gateway { points, index ->
            val seconds = when {
                index == 0 -> listOf(600.0)
                index % 2 == 1 -> listOf(60.0, 12000.0, 18000.0)
                else -> listOf(120.0, 180.0, 240.0)
            }
            RouteEstimateResult.Success(response(points, seconds))
        }
        val result = load(gateway = gateway) as SharedRoutedJourneyLoadResult.Success
        assertEquals(10, result.estimates.directHomeMinutes)
        assertTrue(result.estimates.plans.all { it.drivingMinutes == 10 })
        assertTrue(result.estimates.plans.all { it.driverHomeArrivalMinute == 1097 })
        assertTrue(result.estimates.plans.all { it.driverAdditionalMinutes == 7 })
    }

    @Test fun twoStopsUseWholeRoutesWithoutAnUnnecessarySecondWindow() = runBlocking {
        val gateway = Gateway()
        val result = load(draft.copy(errand = null), gateway) as SharedRoutedJourneyLoadResult.Success
        assertEquals(3, gateway.calls.size)
        assertEquals(2, result.estimates.plans.size)
        assertTrue(result.estimates.windows.values.all { it.size == 1 && it.single().consumedLegIndices == listOf(0, 1, 2) })
        assertTrue(result.estimates.plans.all { it.drivingMinutes == 3 })
    }

    @Test fun onePassengerWithoutAnErrandStillNeedsTheWholeHomeRoute() = runBlocking {
        val gateway = Gateway()
        val result = load(draft.copy(passengers = listOf(first), errand = null), gateway) as SharedRoutedJourneyLoadResult.Success
        assertEquals(listOf(listOf(origin, home), listOf(origin, a, home)), gateway.calls)
        assertEquals(2, result.estimates.plans.single().drivingMinutes)
    }

    @Test fun eachConsumedLegIsRoundedUpSeparately() = runBlocking {
        val gateway = Gateway { points, _ -> RouteEstimateResult.Success(response(points, List(points.size - 1) { 60.1 })) }
        val result = load(gateway = gateway) as SharedRoutedJourneyLoadResult.Success
        assertEquals(2, result.estimates.directHomeMinutes)
        assertTrue(result.estimates.plans.all { it.drivingMinutes == 8 })
    }

    @Test fun anUnverifiedDropoffRemainsAnInvalidPlanEvenWhenRoadQueriesSucceed() = runBlocking {
        val unverified = draft.copy(passengers = draft.passengers.map { it.copy(verification = DropoffVerification()) })
        val result = load(unverified) as SharedRoutedJourneyLoadResult.Success
        assertTrue(result.estimates.plans.all { !it.isValid })
        assertTrue(result.estimates.plans.all { plan -> plan.issues.count { it is SharedPlanIssue.UnverifiedDropoff } == 2 })
        assertTrue(result.estimates.request.passengers.all {
            it.candidates.single().verification.vehicleAccess == AccessCheck.NOT_CHECKED
        })
    }

    @Test fun explicitlyVerifiedDoorstepInputsCanProduceValidPlans() = runBlocking {
        val result = load() as SharedRoutedJourneyLoadResult.Success
        assertTrue(result.estimates.plans.all { it.isValid })
        assertTrue(result.estimates.request.drivingEstimates.isEmpty())
        assertEquals("test-map", result.estimates.dataVersion)
        assertEquals(now, result.estimates.queriedAtEpochMillis)
        assertFalse(result.estimates.includesLiveTraffic)
    }

    @Test fun aFailedNextWindowDoesNotPublishTheEarlierSuccessfulWindows() = runBlocking {
        val gateway = Gateway { points, index ->
            if (index == 2) RouteEstimateResult.Failed(RouteEstimateFailure.NO_ROUTE, "경로가 없어요")
            else RouteEstimateResult.Success(response(points))
        }
        assertTrue(load(gateway = gateway) is SharedRoutedJourneyLoadResult.Failed)
        assertEquals(3, gateway.calls.size)
    }

    @Test fun anOtherwiseValidResponseForADifferentOrderIsRejected() = runBlocking {
        val gateway = Gateway { points, index ->
            val requested = if (index == 1) listOf(points[0], points[2], points[1], points.last()) else points
            RouteEstimateResult.Success(response(requested))
        }
        assertTrue(load(gateway = gateway) is SharedRoutedJourneyLoadResult.Failed)
        assertEquals(2, gateway.calls.size)
    }

    @Test fun sameCoordinatesWithADifferentPoiIdentityAreNotAcceptedAsTheRequestedEndpoint() = runBlocking {
        val gateway = Gateway { points, _ ->
            RouteEstimateResult.Success(response(points.dropLast(1) + points.last().copy(placeId = "other")))
        }
        assertTrue(load(gateway = gateway) is SharedRoutedJourneyLoadResult.Failed)
        assertEquals(1, gateway.calls.size)
    }

    @Test fun mixedDataVersionsAreRejected() = runBlocking {
        val gateway = Gateway { points, index -> RouteEstimateResult.Success(response(points, version = if (index == 2) "other-map" else "test-map")) }
        assertTrue(load(gateway = gateway) is SharedRoutedJourneyLoadResult.Failed)
    }

    @Test fun missingDataVersionsCannotBeClaimedAsOneKnownMapVersion() = runBlocking {
        val gateway = Gateway { points, _ -> RouteEstimateResult.Success(response(points, version = null)) }
        assertTrue(load(gateway = gateway) is SharedRoutedJourneyLoadResult.Failed)
    }

    @Test fun alreadyStaleOrFutureDatedQueriesAreRejected() = runBlocking {
        listOf(now - RouteEstimate.FRESHNESS_MILLIS - 1, now + 1).forEach { timestamp ->
            val gateway = Gateway { points, _ -> RouteEstimateResult.Success(response(points, at = timestamp)) }
            assertTrue(load(gateway = gateway) is SharedRoutedJourneyLoadResult.Failed)
        }
    }

    @Test fun aSetThatExpiresDuringLoadingCannotBePublishedAsFresh() = runBlocking {
        var calls = 0
        val clock = { if (calls++ == 0) now else now + RouteEstimate.FRESHNESS_MILLIS + 1 }
        assertTrue(load(clock = clock) is SharedRoutedJourneyLoadResult.Failed)
    }

    @Test fun theExactFiveMinuteBoundaryRemainsUsable() = runBlocking {
        val gateway = Gateway { points, _ -> RouteEstimateResult.Success(response(points, at = now - RouteEstimate.FRESHNESS_MILLIS)) }
        val result = load(gateway = gateway) as SharedRoutedJourneyLoadResult.Success
        assertTrue(result.estimates.isFresh(now))
        assertFalse(result.estimates.isFresh(now + 1))
    }

    @Test fun anOverlongConsumedLegFailsInsteadOfBeingClamped() = runBlocking {
        val gateway = Gateway { points, index ->
            val seconds = List(points.size - 1) { if (index == 1) 480.0 * 60 + 0.1 else 60.0 }
            RouteEstimateResult.Success(response(points, seconds))
        }
        assertTrue(load(gateway = gateway) is SharedRoutedJourneyLoadResult.Failed)
    }

    @Test fun missingPlacesAndUnconfirmedDoorstepConditionsFailBeforeAnyQuery() = runBlocking {
        val gateway = Gateway { _, _ -> error("must not query") }
        assertTrue(load(draft.copy(driverHome = null), gateway) is SharedRoutedJourneyLoadResult.Failed)
        val result = load(draft.copy(passengers = listOf(first.copy(doorstepDropoffConfirmed = false))), gateway)
            as SharedRoutedJourneyLoadResult.Failed
        assertTrue(result.errors.containsKey("passengers.0.doorstep"))
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun callerCancellationPropagatesWithoutAnErrorOrPartialResult() {
        val gateway = Gateway { _, _ -> throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { runBlocking { load(gateway = gateway) } }
    }

    @Test fun loadedInputIsASnapshotAndCannotSilentlyMatchChangedPassengers() = runBlocking {
        val people = draft.passengers.toMutableList()
        val input = draft.copy(passengers = people)
        val result = load(input) as SharedRoutedJourneyLoadResult.Success
        people.clear()
        assertEquals(2, result.estimates.request.passengers.size)
        assertFalse(result.estimates.matches(input))
        assertTrue(result.estimates.matches(draft))
    }
}
