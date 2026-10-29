package io.moduhome.app.core.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationWindowTest {
    private val home = NavigationPoint("집", 37.57, 126.98)
    private val first = poi("첫 하차", 37.56)
    private val second = poi("둘째 하차", 37.55)
    private val third = poi("장보기", 37.54)
    private val address = NavigationPoint("검색한 주소", 37.53, 126.97)

    @Test fun knownPlacesRetainHomeAndAtMostTwoOrderedWaypoints() {
        assertEquals(NavigationPlan(home, listOf(first, second)), verifiableNavigationWindow(home, listOf(first, second, third)))
        assertEquals(NavigationPlan(home), verifiableNavigationWindow(home, emptyList()))
    }

    @Test fun firstCoordinateOnlyStopIsADestinationInsteadOfAnUnverifiableWaypoint() {
        assertEquals(NavigationPlan(address), verifiableNavigationWindow(home, listOf(address, first, second)))
    }

    @Test fun coordinateOnlyStopAfterOnePoiKeepsThatPoiBeforeTheSegmentDestination() {
        assertEquals(NavigationPlan(address, listOf(first)), verifiableNavigationWindow(home, listOf(first, address, second)))
    }

    @Test fun thirdCoordinateOnlyStopFitsWithTwoKnownWaypointsWithoutLosingTheFinalHome() {
        val stops = listOf(first, second, address)
        assertEquals(NavigationPlan(address, listOf(first, second)), verifiableNavigationWindow(home, stops))
        assertEquals(NavigationPlan(address, listOf(second)), verifiableNavigationWindow(home, stops.drop(1)))
        assertEquals(NavigationPlan(address), verifiableNavigationWindow(home, stops.drop(2)))
        assertEquals(NavigationPlan(home), verifiableNavigationWindow(home, stops.drop(3)))
    }

    @Test fun consecutiveAddressesAreSentInOrderAsSeparateVerifiableDestinations() {
        val other = address.copy(name = "다른 주소", longitude = 126.96)
        assertEquals(NavigationPlan(address), verifiableNavigationWindow(home, listOf(address, other)))
        assertEquals(NavigationPlan(other), verifiableNavigationWindow(home, listOf(other)))
    }

    @Test fun sdkReadbackWithoutWaypointCoordinatesConfirmsTheAddressOnlyAsDestination() {
        val window = verifiableNavigationWindow(home, listOf(first, second, address))
        val readback = NavigationSnapshot(
            ObservedNavigationPlace(address.name, latitude = address.latitude, longitude = address.longitude),
            listOf(ObservedNavigationPlace(first.name, first.placeId), ObservedNavigationPlace(second.name, second.placeId)), true,
        )
        assertTrue(readback.confirms(window))
        assertFalse(readback.copy(destination = ObservedNavigationPlace(address.name)).confirms(window))
        assertFalse(readback.copy(waypoints = readback.waypoints.reversed()).confirms(window))
        assertFalse(readback.copy(destination = ObservedNavigationPlace(address.name, latitude = 37.0, longitude = 127.0)).confirms(window))
    }

    private fun poi(name: String, latitude: Double) = NavigationPoint(name, latitude, 126.98, placeId = name)
}
