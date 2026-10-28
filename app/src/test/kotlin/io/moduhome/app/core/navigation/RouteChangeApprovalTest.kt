package io.moduhome.app.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChangeApprovalTest {
    private val home = NavigationPoint("집", 37.5, 127.0, placeId = "home")
    private val pickup = NavigationPoint("픽업", 37.51, 127.01, placeId = "pickup")
    private val store = NavigationPoint("마트", 37.52, 127.02, placeId = "store")
    private val plan = NavigationPlan(home, listOf(pickup, store))
    private val route = NavigationSnapshot(place(home), listOf(place(pickup), place(store)), true)

    @Test fun createsApprovalOnlyForAStableObservedRevision() {
        val approval = RouteChangeApproval.fromObservation(route, 7, 7)
        assertNotNull(approval)
        assertEquals(route, approval!!.snapshot)
        assertEquals(7L, approval.routeRevision)
        assertNull(RouteChangeApproval.fromObservation(route, 7, 8))
        assertNull(RouteChangeApproval.fromObservation(route, 8, 7))
        assertNull(RouteChangeApproval.fromObservation(route, -1, -1))
        assertNull(RouteChangeApproval.fromObservation(null, 7, 7))
    }

    @Test fun rejectsAChangedRevisionEvenWhenTheRouteReturnedToTheSameIdentity() {
        val approval = RouteChangeApproval.fromObservation(route, 7, 7)!!
        assertTrue(approval.matches(route.copy(), 7))
        assertFalse(approval.matches(route, 8))
        assertFalse(approval.matches(null, 7))
    }

    @Test fun rejectsDestinationOrderAndGuidanceChangesEvenWithoutARevisionEvent() {
        val approval = RouteChangeApproval.fromObservation(route, 7, 7)!!
        assertFalse(approval.matches(route.copy(destination = place(store)), 7))
        assertFalse(approval.matches(route.copy(waypoints = route.waypoints.reversed()), 7))
        assertFalse(approval.matches(route.copy(waypoints = listOf(place(store))), 7))
        assertFalse(approval.matches(route.copy(guidanceActive = false), 7))
    }

    @Test fun acceptsAnObservedIdleRouteButNeverAnUnknownRoute() {
        val idle = NavigationSnapshot(null, emptyList(), false)
        val approval = RouteChangeApproval.fromObservation(idle, 0, 0)!!
        assertTrue(approval.matches(idle, 0))
        assertFalse(approval.matches(route, 0))
    }

    @Test fun snapshotsTheWaypointListBeforeTheDialogCanOutliveItsOwner() {
        val mutableStops = route.waypoints.toMutableList()
        val approval = RouteChangeApproval.fromObservation(route.copy(waypoints = mutableStops), 7, 7)!!
        mutableStops.clear()
        assertEquals(route, approval.snapshot)
    }

    @Test fun removesOnlyCompletedPrefixAndAlwaysKeepsTheOriginalHome() {
        assertEquals(plan, plan.remainingAfter(0))
        assertEquals(NavigationPlan(home, listOf(store)), plan.remainingAfter(1))
        assertEquals(NavigationPlan(home), plan.remainingAfter(2))
        assertEquals(listOf(pickup, store), plan.waypoints)
        assertEquals(NavigationPlan(home), NavigationPlan(home).remainingAfter(0))
    }

    @Test fun refusesImpossibleCompletionCountsInsteadOfDroppingAllVisits() {
        assertThrows(IllegalArgumentException::class.java) { plan.remainingAfter(-1) }
        assertThrows(IllegalArgumentException::class.java) { plan.remainingAfter(3) }
        assertThrows(IllegalArgumentException::class.java) { NavigationPlan(home).remainingAfter(1) }
    }

    private fun place(point: NavigationPoint) = ObservedNavigationPlace(
        point.name, point.placeId, point.provider, point.latitude, point.longitude, point.subId,
    )
}
