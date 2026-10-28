package io.moduhome.app.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceEstimateTest {
    private val home = NavigationPoint("집", 37.5, 127.0, placeId = "home")
    private val pickup = NavigationPoint("픽업", 37.51, 127.01, placeId = "pickup")
    private val store = NavigationPoint("마트", 37.52, 127.02, placeId = "store")
    private val plan = NavigationPlan(home, listOf(pickup, store))
    private val snapshot = NavigationSnapshot(place(home), listOf(place(pickup), place(store)), true)
    private val queriedAt = 100_000L
    private val destination = observation(home, 900, 9_000)
    private val waypoints = listOf(observation(pickup, 100, 1_000), observation(store, 600, 6_000))

    @Test fun preservesCumulativeMetricsWithoutAddingThem() {
        val estimate = (result() as GuidanceEstimateResult.Success).estimate
        assertEquals(900, estimate.destination.remainingSeconds)
        assertEquals(9_000, estimate.destination.remainingMeters)
        assertEquals(listOf(100, 600), estimate.waypoints.map { it.remainingSeconds })
        assertEquals(listOf(pickup, store), estimate.waypoints.map { it.point })
        assertEquals("차량 내비", estimate.source)
        assertEquals(queriedAt + 1, estimate.receivedAtEpochMillis)
    }

    @Test fun acceptsOnlyTheRemainingSuffixAndThenHomeAlone() {
        val remaining = snapshot.copy(waypoints = listOf(place(store)))
        val estimate = (result(stops = listOf(waypoints[1]), before = remaining, after = remaining)
            as GuidanceEstimateResult.Success).estimate
        assertEquals(listOf(store), estimate.waypoints.map { it.point })
        val homeOnly = snapshot.copy(waypoints = emptyList())
        assertTrue(result(stops = emptyList(), before = homeOnly, after = homeOnly)
            is GuidanceEstimateResult.Success)
    }

    @Test fun rejectsReverseOrderMiddleRemovalAndAdditionalStops() {
        for (places in listOf(listOf(place(store), place(pickup)), listOf(place(pickup)),
            listOf(place(pickup), place(store), place(store)))) {
            val route = snapshot.copy(waypoints = places)
            assertTrue(result(before = route, after = route) is GuidanceEstimateResult.Failed)
        }
        val route = snapshot.copy(waypoints = listOf(place(pickup)))
        assertTrue(result(stops = listOf(waypoints[0]), before = route, after = route)
            is GuidanceEstimateResult.Failed)
    }

    @Test fun rejectsWrongPoiEvenWithTheSameNameAndCoordinates() {
        val wrong = destination.copy(place = place(home).copy(placeId = "other"))
        assertTrue(result(dest = wrong) is GuidanceEstimateResult.Failed)
        assertTrue(result(dest = destination.copy(place = place(home).copy(placeId = "")))
            is GuidanceEstimateResult.Failed)
        assertTrue(result(stops = waypoints.reversed()) is GuidanceEstimateResult.Failed)
    }

    @Test fun checksObservationAgainstSnapshotWhenPlanHasNoPoiId() {
        val coordinatePlan = plan.copy(destination = home.copy(placeId = ""))
        assertTrue(GuidanceEstimate.fromObservation(coordinatePlan,
            destination.copy(place = place(home).copy(placeId = "different")), waypoints,
            snapshot, snapshot, queriedAt, queriedAt + 1) is GuidanceEstimateResult.Failed)
    }

    @Test fun rejectsChangedInactiveMissingOrUnconfirmedRoutes() {
        assertTrue(result(changed = true) is GuidanceEstimateResult.Failed)
        assertTrue(result(after = snapshot.copy(waypoints = emptyList())) is GuidanceEstimateResult.Failed)
        assertTrue(result(before = null) is GuidanceEstimateResult.Failed)
        assertTrue(result(after = null) is GuidanceEstimateResult.Failed)
        val inactive = snapshot.copy(guidanceActive = false)
        assertTrue(result(before = inactive, after = inactive) is GuidanceEstimateResult.Failed)
        val anotherHome = snapshot.copy(destination = place(store))
        assertTrue(result(before = anotherHome, after = anotherHome) is GuidanceEstimateResult.Failed)
    }

    @Test fun rejectsMissingObservationsInsteadOfInventingZero() {
        assertTrue(result(dest = null) is GuidanceEstimateResult.Failed)
        assertTrue(result(stops = null) is GuidanceEstimateResult.Failed)
        assertTrue(result(stops = emptyList()) is GuidanceEstimateResult.Failed)
        assertTrue(result(dest = destination.copy(place = null)) is GuidanceEstimateResult.Failed)
    }

    @Test fun stoppedGuidanceClearsOldTimeAndReportsTheActualInactiveRoute() {
        val inactive = snapshot.copy(guidanceActive = false, waypoints = emptyList(), destination = null)
        val failed = result(before = inactive, after = inactive) as GuidanceEstimateResult.Failed
        assertEquals(inactive, failed.observedRoute)
        assertTrue(failed.reason.contains("종료"))
        val success = result() as GuidanceEstimateResult.Success
        assertEquals(snapshot, success.observedRoute)
    }

    @Test fun rejectsNegativeAndNonMonotonicMetrics() {
        assertTrue(result(dest = destination.copy(remainingSeconds = -1)) is GuidanceEstimateResult.Failed)
        assertTrue(result(stops = listOf(waypoints[0].copy(remainingMeters = -1), waypoints[1]))
            is GuidanceEstimateResult.Failed)
        assertTrue(result(dest = destination.copy(remainingSeconds = 500)) is GuidanceEstimateResult.Failed)
        assertTrue(result(dest = destination.copy(remainingMeters = 5_000)) is GuidanceEstimateResult.Failed)
        assertTrue(result(stops = listOf(waypoints[0].copy(remainingSeconds = 700), waypoints[1]))
            is GuidanceEstimateResult.Failed)
    }

    @Test fun rejectsUncertainAllZeroDestinationButAcceptsAStopAtCurrentPosition() {
        assertTrue(result(dest = destination.copy(remainingSeconds = 0, remainingMeters = 0))
            is GuidanceEstimateResult.Failed)
        assertTrue(result(stops = listOf(waypoints[0].copy(remainingSeconds = 0, remainingMeters = 0), waypoints[1]))
            is GuidanceEstimateResult.Success)
    }

    @Test fun rejectsZeroTimeForAnyPointThatStillHasDistance() {
        assertTrue(result(dest = destination.copy(remainingSeconds = 0)) is GuidanceEstimateResult.Failed)
        for (index in waypoints.indices) {
            val stops = waypoints.mapIndexed { i, stop ->
                if (i == index) stop.copy(remainingSeconds = 0) else stop
            }
            assertTrue(result(stops = stops) is GuidanceEstimateResult.Failed)
        }
    }

    @Test fun rejectsDifferentKnownSubIdsButPreservesTheExistingBlankFallback() {
        val identifiedHome = home.copy(subId = "entrance-a")
        val identifiedPlan = plan.copy(destination = identifiedHome)
        val identifiedSnapshot = snapshot.copy(destination = place(identifiedHome))
        fun check(subId: String) = GuidanceEstimate.fromObservation(
            identifiedPlan, destination.copy(place = place(identifiedHome).copy(subId = subId)),
            waypoints, identifiedSnapshot, identifiedSnapshot, queriedAt, queriedAt + 1,
        )
        assertTrue(check("entrance-b") is GuidanceEstimateResult.Failed)
        assertTrue(check("") is GuidanceEstimateResult.Success)
        assertTrue(check("entrance-a") is GuidanceEstimateResult.Success)
        assertTrue(result(dest = destination.copy(place = place(home).copy(subId = "entrance-b")))
            is GuidanceEstimateResult.Success)
    }

    @Test fun rejectsOldQueriesAndClockReversalWithInclusiveDeadline() {
        assertTrue(result(receivedAt = queriedAt + 10_000) is GuidanceEstimateResult.Success)
        assertTrue(result(receivedAt = queriedAt + 10_001) is GuidanceEstimateResult.Failed)
        assertTrue(result(receivedAt = queriedAt - 1) is GuidanceEstimateResult.Failed)
        assertTrue(GuidanceEstimate.fromObservation(plan, destination, waypoints, snapshot, snapshot, -1, 0)
            is GuidanceEstimateResult.Failed)
    }

    @Test fun expiresAfterSixtySecondsAndRejectsFutureReceiptTime() {
        val estimate = (result() as GuidanceEstimateResult.Success).estimate
        val receivedAt = estimate.receivedAtEpochMillis
        assertTrue(estimate.isFresh(receivedAt + 60_000))
        assertFalse(estimate.isFresh(receivedAt + 60_001))
        assertFalse(estimate.isFresh(receivedAt - 1))
    }

    private fun result(
        dest: GuidancePointObservation? = destination,
        stops: List<GuidancePointObservation>? = waypoints,
        before: NavigationSnapshot? = snapshot,
        after: NavigationSnapshot? = snapshot,
        receivedAt: Long = queriedAt + 1,
        changed: Boolean = false,
    ) = GuidanceEstimate.fromObservation(plan, dest, stops, before, after, queriedAt, receivedAt, changed)

    private fun observation(point: NavigationPoint, seconds: Int, meters: Int) =
        GuidancePointObservation(place(point), seconds, meters)

    private fun place(point: NavigationPoint) = ObservedNavigationPlace(
        point.name, point.placeId, point.provider, point.latitude, point.longitude, point.subId,
    )
}
