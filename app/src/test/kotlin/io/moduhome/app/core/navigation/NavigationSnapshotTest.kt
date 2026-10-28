package io.moduhome.app.core.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSnapshotTest {
    private val home = NavigationPoint("집", 37.5, 127.0, placeId = "home")
    private val pickup = NavigationPoint("픽업", 37.51, 127.01, placeId = "pickup")
    private val groceries = NavigationPoint("마트", 37.52, 127.02, placeId = "groceries")
    private val plan = NavigationPlan(home, listOf(pickup, groceries))
    private fun observed(point: NavigationPoint) =
        ObservedNavigationPlace(point.name, point.placeId, point.provider, point.latitude, point.longitude, point.subId)
    private val snapshot = NavigationSnapshot(observed(home), listOf(observed(pickup), observed(groceries)), guidanceActive = true)

    @Test fun confirmsTheDestinationAndBothWaypointsInOrder() {
        assertTrue(snapshot.confirms(plan))
    }

    @Test fun reversedReadbackDoesNotConfirmTheRequestedOrder() {
        assertFalse(snapshot.copy(waypoints = listOf(observed(groceries), observed(pickup))).confirms(plan))
    }

    @Test fun missingAndExtraWaypointsDoNotConfirm() {
        assertFalse(snapshot.copy(waypoints = listOf(observed(pickup))).confirms(plan))
        assertFalse(snapshot.copy(waypoints = listOf(observed(pickup), observed(groceries), observed(home))).confirms(plan))
    }

    @Test fun aWaypointCannotBeMistakenForTheFinalDestination() {
        assertFalse(snapshot.copy(destination = observed(pickup)).confirms(plan))
        assertFalse(snapshot.copy(destination = null).confirms(plan))
    }

    @Test fun readbackWithoutActiveGuidanceDoesNotConfirm() {
        assertFalse(snapshot.copy(guidanceActive = false).confirms(plan))
    }

    @Test fun coordinateRoundingAndProviderNamingDoNotInvalidateTheSamePlace() {
        assertTrue(snapshot.copy(destination = observed(home).copy(name = "내비가 반환한 이름", placeId = "",
            latitude = 37.5000001)).confirms(plan))
    }

    @Test fun stableIdsAllowTheRoadEntranceCoordinateToDifferFromTheCatalog() {
        assertTrue(snapshot.copy(destination = observed(home).copy(latitude = 37.5003, longitude = 127.0003)).confirms(plan))
    }

    @Test fun adjacentShopsWithDifferentProviderIdsRemainDifferentPlaces() {
        val identified = pickup.copy(placeId = "shop-a", provider = "map")
        assertFalse(observed(identified).copy(placeId = "shop-b").matches(identified))
    }

    @Test fun missingWaypointCoordinatesCanBeVerifiedByTheirActualIds() {
        val actual = snapshot.copy(waypoints = snapshot.waypoints.map { it.copy(latitude = null, longitude = null) })
        assertTrue(actual.confirms(plan))
        assertFalse(actual.copy(waypoints = actual.waypoints.reversed()).confirms(plan))
    }

    @Test fun aNameWithoutCoordinatesOrAnIdCannotConfirmAPlace() {
        assertFalse(snapshot.copy(waypoints = listOf(ObservedNavigationPlace(pickup.name), observed(groceries))).confirms(plan))
    }

    @Test fun coordinateFallbackRejectsAFarAwayPoint() {
        assertFalse(snapshot.copy(destination = observed(home).copy(placeId = "", latitude = 37.6)).confirms(plan))
    }

    @Test fun differentProvidersAndKnownEntranceIdsCannotBeConflated() {
        val requested = home.copy(provider = "map-a", subId = "door-a")
        assertFalse(observed(requested).copy(provider = "map-b").matches(requested))
        assertFalse(observed(requested).copy(subId = "door-b").matches(requested))
    }

    @Test fun rejectsMoreThanTwoWaypointsAndNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { NavigationPlan(home, listOf(pickup, groceries, home)) }
        assertThrows(IllegalArgumentException::class.java) { NavigationPoint("장소", Double.NaN, 127.0) }
        assertThrows(IllegalArgumentException::class.java) { NavigationPoint("장소", 37.5, Double.POSITIVE_INFINITY) }
    }
}
