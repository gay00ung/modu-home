package io.moduhome.app.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEstimateTest {
    private val home = NavigationPoint("집", 37.5, 127.0, placeId = "home")
    private val pickup = NavigationPoint("픽업", 37.51, 127.01, placeId = "pickup")
    private val groceries = NavigationPoint("마트", 37.52, 127.02, placeId = "groceries")
    private val requestedAt = 1_800_000_000_000L
    private val idle = NavigationSnapshot(null, emptyList(), guidanceActive = false)
    private val guiding = NavigationSnapshot(observed(home), listOf(observed(pickup), observed(groceries)), true)

    @Test fun acceptsAMatchedDestinationAndPreservesSecondsMetersAndQueryTime() {
        val result = response() as TripEstimateResult.Success
        assertEquals(home, result.estimate.destination)
        assertEquals(93, result.estimate.durationSeconds)
        assertEquals(1_234, result.estimate.distanceMeters)
        assertEquals(requestedAt, result.estimate.queriedAtEpochMillis)
    }

    @Test fun acceptsAPreviewWhileExistingGuidanceRemainsUnchanged() {
        assertTrue(response(before = guiding, after = guiding) is TripEstimateResult.Success)
    }

    @Test fun rejectsAnotherDestinationEvenWhenItsNameMatches() {
        val anotherShop = observed(groceries).copy(name = home.name)
        assertTrue(response(destination = anotherShop) is TripEstimateResult.Failed)
    }

    @Test fun rejectsAMissingDestinationOrANameWithoutIdentity() {
        assertTrue(response(destination = null) is TripEstimateResult.Failed)
        assertTrue(response(destination = ObservedNavigationPlace(home.name)) is TripEstimateResult.Failed)
    }

    @Test fun acceptsAnIdentifiedRoadEntranceAndCoordinateOnlyRounding() {
        assertTrue(response(destination = observed(home).copy(latitude = 37.501)) is TripEstimateResult.Success)
        assertTrue(response(expected = home.copy(placeId = ""),
            destination = observed(home).copy(placeId = "", latitude = 37.5000001)) is TripEstimateResult.Success)
    }

    @Test fun requiresTheKnownPoiIdEvenWhenItsCoordinatesAreNearby() {
        assertTrue(response(destination = observed(home).copy(placeId = "")) is TripEstimateResult.Failed)
        assertTrue(response(destination = observed(home).copy(placeId = "next-door-shop")) is TripEstimateResult.Failed)
    }

    @Test fun rejectsFarAwayCoordinatesWhenTheProviderDidNotSupplyAnId() {
        assertTrue(response(destination = observed(home).copy(placeId = "", latitude = 38.0)) is TripEstimateResult.Failed)
    }

    @Test fun rejectsNegativeTimeAndDistanceInsteadOfDisplayingThem() {
        assertTrue(response(seconds = -1) is TripEstimateResult.Failed)
        assertTrue(response(meters = -1) is TripEstimateResult.Failed)
    }

    @Test fun acceptsZeroForATargetAtTheCurrentPosition() {
        assertTrue(response(seconds = 0, meters = 0) is TripEstimateResult.Success)
    }

    @Test fun rejectsAnOldResponseAndAClockThatMovedBackDuringTheRequest() {
        assertTrue(response(observedAt = requestedAt + TripEstimate.FRESHNESS_MILLIS + 1) is TripEstimateResult.Failed)
        assertTrue(response(observedAt = requestedAt - 1) is TripEstimateResult.Failed)
    }

    @Test fun doesNotConfirmAPreviewWithoutBothNavigationReadbacks() {
        assertTrue(response(before = null) is TripEstimateResult.Failed)
        assertTrue(response(after = null) is TripEstimateResult.Failed)
    }

    @Test fun rejectsAPreviewThatStartedOrStoppedGuidance() {
        assertTrue(response(after = idle.copy(guidanceActive = true)) is TripEstimateResult.Failed)
        assertTrue(response(before = guiding, after = guiding.copy(guidanceActive = false)) is TripEstimateResult.Failed)
    }

    @Test fun rejectsAPreviewThatReplacedTheDestination() {
        assertTrue(response(before = guiding, after = guiding.copy(destination = observed(groceries))) is TripEstimateResult.Failed)
    }

    @Test fun rejectsAPreviewThatChangedTheOrderedWaypoints() {
        assertTrue(response(before = guiding, after = guiding.copy(waypoints = guiding.waypoints.reversed())) is TripEstimateResult.Failed)
        assertTrue(response(before = guiding, after = guiding.copy(waypoints = emptyList())) is TripEstimateResult.Failed)
    }

    @Test fun aDisplayedEstimateExpiresWithoutBeingRelabelledAsANewQuery() {
        val estimate = (response() as TripEstimateResult.Success).estimate
        assertTrue(estimate.isFresh(requestedAt))
        assertTrue(estimate.isFresh(requestedAt + TripEstimate.FRESHNESS_MILLIS))
        assertFalse(estimate.isFresh(requestedAt + TripEstimate.FRESHNESS_MILLIS + 1))
        assertFalse(estimate.isFresh(requestedAt - 1))
        assertEquals(requestedAt, estimate.queriedAtEpochMillis)
    }

    @Test fun invalidEstimatesCannotBeConstructedOrCopiedIntoValidOnes() {
        assertThrows(IllegalArgumentException::class.java) { TripEstimate(home, -1, 1, requestedAt) }
        assertThrows(IllegalArgumentException::class.java) { TripEstimate(home, 1, -1, requestedAt) }
        assertThrows(IllegalArgumentException::class.java) { TripEstimate(home, 1, 1, -1) }
        val estimate = TripEstimate(home, 1, 1, requestedAt)
        assertThrows(IllegalArgumentException::class.java) { estimate.copy(durationSeconds = -1) }
    }

    private fun observed(point: NavigationPoint) = ObservedNavigationPlace(
        point.name, point.placeId, point.provider, point.latitude, point.longitude, point.subId,
    )

    private fun response(
        expected: NavigationPoint = home,
        destination: ObservedNavigationPlace? = observed(home),
        seconds: Int = 93,
        meters: Int = 1_234,
        observedAt: Long = requestedAt + 500,
        before: NavigationSnapshot? = idle,
        after: NavigationSnapshot? = idle,
    ): TripEstimateResult = TripEstimate.fromObservation(
        destination = expected,
        observedDestination = destination,
        durationSeconds = seconds,
        distanceMeters = meters,
        queriedAtEpochMillis = requestedAt,
        observedAtEpochMillis = observedAt,
        routeBefore = before,
        routeAfter = after,
    )
}
