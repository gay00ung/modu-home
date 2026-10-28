package io.moduhome.app.core.routing

import io.moduhome.app.core.navigation.NavigationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEstimateTest {
    private val now = 1_800_000_000_000L
    private val origin = NavigationPoint("출발지", 37.55579, 126.97034)
    private val pickup = NavigationPoint("픽업", 37.57131, 126.97635)
    private val home = NavigationPoint("목적지", 37.56667, 126.97819)
    private val points = listOf(origin, pickup, home)
    private val observations = listOf(ObservedRouteLeg(101.2, 1_002.3), ObservedRouteLeg(202.3, 2_003.4))

    private fun response(
        orderedPoints: List<NavigationPoint> = points,
        legs: List<ObservedRouteLeg> = observations,
        snapped: List<SnappedRoutePoint> = orderedPoints.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
        duration: Double = legs.sumOf { it.durationSeconds },
        distance: Double = legs.sumOf { it.distanceMeters },
        queriedAt: Long = now,
        version: String? = "2026-10-20T00:00:00Z",
    ): RouteEstimateResult = RouteEstimate.fromObservation(orderedPoints, legs, snapped, duration, distance, queriedAt, version)

    private fun failure(result: RouteEstimateResult): RouteEstimateFailure {
        assertTrue(result is RouteEstimateResult.Failed)
        return (result as RouteEstimateResult.Failed).kind
    }

    @Test fun everyLegBelongsToTheExactRequestedDirectionAndOrder() {
        val estimate = (response() as RouteEstimateResult.Success).estimate
        assertEquals(points, estimate.points)
        assertEquals(RouteEstimateLeg(origin, pickup, 101.2, 1_002.3), estimate.legs[0])
        assertEquals(RouteEstimateLeg(pickup, home, 202.3, 2_003.4), estimate.legs[1])
        assertEquals(303.5, estimate.durationSeconds, 0.00001)
        assertEquals(3_005.7, estimate.distanceMeters, 0.00001)
    }

    @Test fun reversingAVisitDoesNotReuseTheOldLegsOrAssumeTheSameDuration() {
        val first = (response() as RouteEstimateResult.Success).estimate
        val otherOrder = listOf(origin, home, pickup)
        val otherLegs = listOf(ObservedRouteLeg(90.0, 900.0), ObservedRouteLeg(250.0, 2_500.0))
        val other = (response(otherOrder, otherLegs) as RouteEstimateResult.Success).estimate
        assertEquals(listOf(origin to pickup, pickup to home), first.legs.map { it.from to it.to })
        assertEquals(listOf(origin to home, home to pickup), other.legs.map { it.from to it.to })
        assertEquals(340.0, other.durationSeconds, 0.0)
        assertEquals(303.5, first.durationSeconds, 0.00001)
    }

    @Test fun missingLegOrWaypointInvalidatesTheEntireResponseInsteadOfUsingZero() {
        assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(legs = observations.take(1))))
        assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(snapped = emptyList())))
        assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(legs = observations + observations.first())))
    }

    @Test fun invalidAndInfiniteDurationOrDistanceCannotEnterThePlan() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1).forEach { invalid ->
            assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(duration = invalid)))
            assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(distance = invalid)))
            assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(legs = listOf(ObservedRouteLeg(invalid, 10.0), observations[1]))))
            assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(legs = listOf(ObservedRouteLeg(10.0, invalid), observations[1]))))
        }
    }

    @Test fun claimedTotalsMustMatchTheSuppliedLegs() {
        assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(duration = 1_000.0)))
        assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(distance = 1_000.0)))
        assertTrue(response(duration = 303.6, distance = 3_005.8) is RouteEstimateResult.Success)
    }

    @Test fun snappingTooFarFromTheRequestedPlaceIsRejected() {
        val snapped = points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) }.toMutableList()
        snapped[1] = snapped[1].copy(distanceMeters = RouteEstimate.MAX_SNAP_METERS + 0.01)
        assertEquals(RouteEstimateFailure.SNAP_TOO_FAR, failure(response(snapped = snapped)))
    }

    @Test fun aFalseSmallSnapDistanceCannotHideAReorderedOrDistantWaypoint() {
        val wrongOrder = listOf(points[1], points[0], points[2]).map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) }
        assertEquals(RouteEstimateFailure.SNAP_TOO_FAR, failure(response(snapped = wrongOrder)))
    }

    @Test fun exactSnappingBoundaryIsAllowed() {
        val snapped = points.map { SnappedRoutePoint(it.latitude, it.longitude, RouteEstimate.MAX_SNAP_METERS) }
        assertTrue(response(snapped = snapped) is RouteEstimateResult.Success)
    }

    @Test fun invalidSnapCoordinatesAndMetricsAreRejected() {
        val valid = points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) }
        listOf(
            valid[0].copy(latitude = Double.NaN),
            valid[0].copy(longitude = 181.0),
            valid[0].copy(distanceMeters = Double.POSITIVE_INFINITY),
            valid[0].copy(distanceMeters = -1.0),
        ).forEach { invalid ->
            assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(snapped = listOf(invalid) + valid.drop(1))))
        }
    }

    @Test fun zeroIsAcceptedOnlyWhenItWasExplicitlyReturnedForTheCompleteRoute() {
        val samePlace = listOf(origin, origin)
        val zero = response(samePlace, listOf(ObservedRouteLeg(0.0, 0.0))) as RouteEstimateResult.Success
        assertEquals(0.0, zero.estimate.durationSeconds, 0.0)
        assertEquals(0.0, zero.estimate.distanceMeters, 0.0)
    }

    @Test fun metadataSeparatesRoadNetworkTimeFromLiveTrafficAndRetainsTheMapDate() {
        val estimate = (response() as RouteEstimateResult.Success).estimate
        assertEquals("OSRM · OpenStreetMap", estimate.source)
        assertFalse(estimate.includesLiveTraffic)
        assertEquals(now, estimate.queriedAtEpochMillis)
        assertEquals("2026-10-20T00:00:00Z", estimate.dataVersion)
        assertTrue(estimate.isFresh(now + RouteEstimate.FRESHNESS_MILLIS))
        assertFalse(estimate.isFresh(now + RouteEstimate.FRESHNESS_MILLIS + 1))
        assertFalse(estimate.isFresh(now - 1))
    }

    @Test fun anAbsentMapVersionRemainsAbsentAndMalformedMetadataIsRejected() {
        assertEquals(null, (response(version = null) as RouteEstimateResult.Success).estimate.dataVersion)
        assertEquals(RouteEstimateFailure.INVALID_RESPONSE, failure(response(version = "\n2026")))
        assertEquals(RouteEstimateFailure.INVALID_REQUEST, failure(response(queriedAt = -1)))
    }

    @Test fun inputMutationAfterAResponseCannotChangeTheEstimate() {
        val mutablePoints = points.toMutableList()
        val mutableLegs = observations.toMutableList()
        val estimate = (response(mutablePoints, mutableLegs) as RouteEstimateResult.Success).estimate
        mutablePoints.clear()
        mutableLegs.clear()
        assertEquals(points, estimate.points)
        assertEquals(2, estimate.legs.size)
    }

    @Test fun validatedSnappingEvidenceSurvivesSnapshotReconstructionWithoutInventingZeroDistance() {
        val snaps = points.map { SnappedRoutePoint(it.latitude + 0.0001, it.longitude, 11.12) }
        val initial = (response(snapped = snaps) as RouteEstimateResult.Success).estimate
        val restored = RouteEstimate.fromObservation(initial.points,
            initial.legs.map { ObservedRouteLeg(it.durationSeconds, it.distanceMeters) },
            initial.snappedPoints, initial.durationSeconds, initial.distanceMeters,
            initial.queriedAtEpochMillis, initial.dataVersion) as RouteEstimateResult.Success
        assertEquals(snaps, restored.estimate.snappedPoints)
        assertEquals(initial.legs, restored.estimate.legs)
        assertEquals(initial.points, restored.estimate.points)
        assertEquals(initial.queriedAtEpochMillis, restored.estimate.queriedAtEpochMillis)
        assertEquals(initial.dataVersion, restored.estimate.dataVersion)
    }

    @Test fun routeInputIsBoundedAndNeedsBothOriginAndDestination() {
        assertEquals(RouteEstimateFailure.INVALID_REQUEST, failure(response(orderedPoints = listOf(origin))))
        assertEquals(RouteEstimateFailure.INVALID_REQUEST, failure(response(orderedPoints = List(RouteEstimate.MAX_ROUTE_POINTS + 1) { origin })))
    }
}
