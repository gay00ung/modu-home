package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.ErrandJourneyPlanner
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationPlanTest {
    private val home = NavigationPoint("집", 37.5, 127.0)
    private val pickup = NavigationPoint("픽업", 37.51, 127.01, placeId = "pickup")
    private val groceries = NavigationPoint("마트", 37.52, 127.02, placeId = "groceries")
    private val draft = JourneyDraft(
        home = home.name,
        pickup = VisitDraft(place = pickup.name, stay = "5", ready = true),
        groceries = VisitDraft(place = groceries.name, stay = "15"),
        budget = 60,
        navigationPlaces = NavigationPlaces(home, pickup, groceries),
    )
    private fun plans(input: JourneyDraft = draft) = ErrandJourneyPlanner().compare(checkNotNull(input.validate().request))

    @Test fun pickupFirstPlanKeepsHomeAsTheFinalDestination() {
        val plan = plans().single { it.visits.first().visit.kind == VisitKind.PICKUP }
        val navigation = checkNotNull(draft.navigationPlan(plan))
        assertEquals(home, navigation.destination)
        assertEquals(listOf(pickup, groceries), navigation.waypoints)
    }

    @Test fun groceriesFirstPlanChangesBothWaypointPositions() {
        val plan = plans().single { it.visits.first().visit.kind == VisitKind.GROCERIES }
        assertEquals(listOf(groceries, pickup), draft.navigationPlan(plan)?.waypoints)
    }

    @Test fun aPlaceNameWithoutCoordinatesCannotBeSentToNavigation() {
        assertNull(draft.copy(navigationPlaces = NavigationPlaces()).navigationPlan(plans().first()))
    }

    @Test fun editingAPlaceNameInvalidatesItsOldCoordinates() {
        val changed = draft.copy(home = "다른 집").clearUnmatchedPlaces()
        assertNull(changed.navigationPlaces.home)
        assertEquals(pickup, changed.navigationPlaces.pickup)
        assertNull(changed.navigationPlan(plans().first()))
    }

    @Test fun disabledErrandsDoNotRequireUnusedCoordinates() {
        val changed = draft.copy(groceries = draft.groceries.copy(enabled = false),
            navigationPlaces = draft.navigationPlaces.copy(groceries = null))
        assertEquals(listOf(pickup), changed.navigationPlan(plans(changed).single())?.waypoints)
    }

    @Test fun selectingANavigationPlaceRequiresFreshPickupConfirmation() {
        val changed = draft.chooseNavigationPlace(NavigationPlaceRole.PICKUP, pickup.copy(placeId = "another-shop"))
        assertFalse(changed.pickup.ready)
        assertEquals("another-shop", changed.navigationPlaces.pickup?.placeId)
    }

    @Test fun surroundingWhitespaceKeepsTheSelectedPlace() {
        val changed = draft.copy(home = " 집 ").clearUnmatchedPlaces()
        assertEquals(home, changed.navigationPlaces.home)
    }

    @Test fun twoNewAddressesBecomeSeparateDestinationsBeforeTheOriginalHome() {
        val addressPickup = pickup.copy(placeId = "")
        val addressGroceries = groceries.copy(placeId = "")
        val addresses = draft.copy(navigationPlaces = NavigationPlaces(home, addressPickup, addressGroceries))
        val selected = plans(addresses).single { it.visits.first().visit.kind == VisitKind.PICKUP }
        assertEquals(addressPickup, addresses.navigationPlan(selected, 0)?.destination)
        assertEquals(emptyList<NavigationPoint>(), addresses.navigationPlan(selected, 0)?.waypoints)
        assertEquals(addressGroceries, addresses.navigationPlan(selected, 1)?.destination)
        assertEquals(emptyList<NavigationPoint>(), addresses.navigationPlan(selected, 1)?.waypoints)
        assertEquals(home, addresses.navigationPlan(selected, 2)?.destination)
    }

    @Test fun aKnownPoiCanPrecedeAnAddressAndIsDroppedBeforeBuildingTheNextWindow() {
        val address = groceries.copy(placeId = "")
        val mixed = draft.copy(navigationPlaces = NavigationPlaces(home, pickup, address))
        val selected = plans(mixed).single { it.visits.first().visit.kind == VisitKind.PICKUP }
        assertEquals(listOf(pickup), mixed.navigationPlan(selected)?.waypoints)
        assertEquals(address, mixed.navigationPlan(selected)?.destination)
        assertEquals(emptyList<NavigationPoint>(), mixed.navigationPlan(selected, 1)?.waypoints)
        assertEquals(address, mixed.navigationPlan(selected, 1)?.destination)
        assertEquals(home, mixed.navigationPlan(selected, 2)?.destination)
    }

    @Test fun invalidCompletionCountsCannotCreateARouteOrSkipHomeValidation() {
        val selected = plans().first()
        assertNull(draft.navigationPlan(selected, -1))
        assertNull(draft.navigationPlan(selected, 3))
        assertNull(draft.copy(navigationPlaces = draft.navigationPlaces.copy(home = null)).navigationPlan(selected, 2))
    }
}
