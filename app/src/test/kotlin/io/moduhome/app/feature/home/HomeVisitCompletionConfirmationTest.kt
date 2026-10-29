package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.ObservedNavigationPlace
import io.moduhome.app.core.navigation.RouteChangeApproval
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.vehicle.DrivingInteraction
import org.junit.Assert.*
import org.junit.Test

class HomeVisitCompletionConfirmationTest {
    private val home = NavigationPoint("집", 37.5, 127.0, placeId = "home")
    private val pickup = NavigationPoint("픽업", 37.51, 127.01, placeId = "pickup")
    private val groceries = NavigationPoint("장보기", 37.52, 127.02, placeId = "groceries")
    private val editable = DrivingInteraction.Known(false)
    private val inactive = NavigationSnapshot(null, emptyList(), false)

    private fun active(addresses: Boolean = false): HomeUiState {
        val draft = JourneyDraft(home = home.name, budget = 60,
            pickup = VisitDraft(place = pickup.name, stay = "5", ready = true),
            groceries = VisitDraft(place = groceries.name, stay = "15"),
            navigationPlaces = NavigationPlaces(home, pickup.copy(placeId = if (addresses) "" else pickup.placeId),
                groceries.copy(placeId = if (addresses) "" else groceries.placeId)))
        val input = HomeUiState(draft)
        val selected = input.plans.single { it.visits.first().visit.kind == VisitKind.PICKUP }
        return input.copy(progress = JourneyProgress().compare(input.plans).select(selected.id, input.plans)
            .review(input.plans).start(input.plans, 100))
    }
    private fun NavigationPoint.observed() = ObservedNavigationPlace(name, placeId, provider, latitude, longitude, subId)
    private fun observed(plan: NavigationPlan) = NavigationSnapshot(plan.destination.observed(), plan.waypoints.map { it.observed() }, true)
    private fun confirmation(state: HomeUiState, snapshot: NavigationSnapshot = observed(checkNotNull(state.navigationWindow))) =
        HomeVisitCompletionConfirmation.fromObservation(state, VisitKind.PICKUP, editable, snapshot, 7, 7)

    @Test fun completionRecordsOneVisitBeforeAdvancingToTheNextAddress() {
        val state = active(addresses = true)
        val snapshot = observed(checkNotNull(state.navigationWindow))
        val pending = checkNotNull(confirmation(state, snapshot))
        assertEquals(0, state.progress.completedVisits)
        val completed = checkNotNull(pending.afterRead(state, editable, snapshot, 7, 7, 200))
        assertEquals(1, completed.state.progress.completedVisits)
        assertEquals(groceries.copy(placeId = ""), completed.state.navigationWindow?.destination)
        assertEquals(home, completed.state.draft.navigationPlaces.home)
        assertTrue(completed.approval.matches(snapshot, 7))
        assertNull(pending.afterRead(completed.state, editable, snapshot, 7, 7, 200))
    }

    @Test fun endedTerminalGuidanceNeedsAnExplicitConfirmationBeforeHomeIsReachable() {
        val state = active(addresses = true)
        val pending = checkNotNull(confirmation(state, inactive))
        assertTrue(pending.guidanceEnded)
        assertEquals(pickup.name, pending.placeName)
        assertEquals(0, state.progress.completedVisits)
        val first = checkNotNull(pending.afterRead(state, editable, inactive, 7, 7, 200)).state
        val second = checkNotNull(HomeVisitCompletionConfirmation.fromObservation(first, VisitKind.GROCERIES,
            editable, inactive, 8, 8))
        val allDone = checkNotNull(second.afterRead(first, editable, inactive, 8, 8, 300)).state
        assertEquals(2, allDone.progress.completedVisits)
        assertEquals(NavigationPlan(home), allDone.navigationWindow)
    }

    @Test fun endedGuidanceCanOnlyRecordKnownVisitsWithoutRestartingThem() {
        val state = active()
        val pending = checkNotNull(confirmation(state, inactive))
        assertFalse(pending.navigationRequired)
        assertEquals(0, state.progress.completedVisits)
        val first = checkNotNull(pending.afterRead(state, editable, inactive, 7, 7, 200))
        assertEquals(1, first.state.progress.completedVisits)
        assertFalse(first.navigationRequired)
        val second = checkNotNull(HomeVisitCompletionConfirmation.fromObservation(first.state, VisitKind.GROCERIES,
            editable, inactive, 8, 8))
        val allDone = checkNotNull(second.afterRead(first.state, editable, inactive, 8, 8, 300))
        assertFalse(allDone.navigationRequired)
        assertEquals(2, allDone.state.progress.completedVisits)
        assertEquals(NavigationPlan(home), allDone.state.navigationWindow)
    }

    @Test fun nativeConsumptionOfAnOrderedWaypointPrefixStillAllowsUserConfirmation() {
        val state = active()
        val full = observed(checkNotNull(state.navigationWindow))
        val consumed = full.copy(waypoints = full.waypoints.drop(1))
        val pending = checkNotNull(confirmation(state, consumed))
        assertEquals(1, pending.afterRead(state, editable, consumed, 7, 7, 200)?.state?.progress?.completedVisits)
        assertNull(confirmation(state, full.copy(waypoints = full.waypoints.take(1))))
    }

    @Test fun aNativeRouteAlreadyAheadOfTheRecordsNeverReaddsVisitedWaypoints() {
        val state = active()
        val homeOnly = observed(NavigationPlan(home))
        val pending = checkNotNull(confirmation(state, homeOnly))
        val completion = checkNotNull(pending.afterRead(state, editable, homeOnly, 7, 7, 200))
        assertEquals(listOf(groceries), completion.state.navigationWindow?.waypoints)
        assertFalse(completion.navigationRequired)
        assertEquals(1, completion.state.progress.completedVisits)
    }

    @Test fun anAlreadyCorrectRemainingRouteNeedsNoExternalTransmission() {
        val state = active()
        val actual = observed(NavigationPlan(home, listOf(groceries)))
        val pending = checkNotNull(confirmation(state, actual))
        assertFalse(checkNotNull(pending.afterRead(state, editable, actual, 7, 7, 200)).navigationRequired)
    }

    @Test fun manualCompletionBeforeNativeConsumptionDropsTheCompletedWaypointExplicitly() {
        val state = active()
        val actual = observed(checkNotNull(state.navigationWindow))
        val pending = checkNotNull(confirmation(state, actual))
        val completion = checkNotNull(pending.afterRead(state, editable, actual, 7, 7, 200))
        assertTrue(completion.navigationRequired)
        assertEquals(NavigationPlan(home, listOf(groceries)), completion.state.navigationWindow)
    }

    @Test fun aConsumedKnownPrefixKeepsTheAddressDestinationUntilTheUserFinishesIt() {
        val source = active()
        val state = source.copy(draft = source.draft.copy(navigationPlaces = NavigationPlaces(home, pickup, groceries.copy(placeId = ""))))
        val actual = observed(NavigationPlan(groceries.copy(placeId = "")))
        val pending = checkNotNull(confirmation(state, actual))
        val completion = checkNotNull(pending.afterRead(state, editable, actual, 7, 7, 200))
        assertFalse(completion.navigationRequired)
        assertEquals(actual.destination?.name, completion.state.navigationWindow?.destination?.name)
        val terminal = checkNotNull(HomeVisitCompletionConfirmation.fromObservation(completion.state,
            VisitKind.GROCERIES, editable, inactive, 8, 8))
        assertTrue(terminal.navigationRequired)
        assertEquals(NavigationPlan(home), terminal.nextWindow)
    }

    @Test fun theSamePlaceNameAndZeroCoordinatesCannotVerifyANewAddress() {
        val state = active(addresses = true)
        assertNull(confirmation(state, NavigationSnapshot(
            ObservedNavigationPlace(pickup.name, latitude = 0.0, longitude = 0.0), emptyList(), true)))
        assertNull(HomeVisitCompletionConfirmation.fromObservation(state, VisitKind.PICKUP, editable, null, 7, 7))
    }

    @Test fun aChangedItineraryCannotUseAnOldCompletionEvenWithTheSameProgress() {
        val state = active()
        val snapshot = observed(checkNotNull(state.navigationWindow))
        val pending = checkNotNull(confirmation(state, snapshot))
        assertNull(pending.afterRead(state.copy(draft = state.draft.copy(budget = 90)), editable, snapshot, 7, 7, 200))
        assertNull(pending.afterRead(state, editable, snapshot.copy(destination = pickup.observed()), 7, 7, 200))
    }

    @Test fun allRouteRevisionChangesInvalidateTheUserClaim() {
        val state = active()
        val snapshot = observed(checkNotNull(state.navigationWindow))
        val pending = checkNotNull(confirmation(state, snapshot))
        assertNull(pending.afterRead(state, editable, snapshot, 8, 8, 200))
        assertNull(pending.afterRead(state, editable, snapshot, 7, 8, 200))
        assertNull(HomeVisitCompletionConfirmation.fromObservation(state, VisitKind.PICKUP, editable, snapshot, 7, 8))
    }

    @Test fun unknownRestrictedAndChangedEditableDisplayResponsesInvalidateTheClaim() {
        val state = active()
        val snapshot = observed(checkNotNull(state.navigationWindow))
        val pending = checkNotNull(confirmation(state, snapshot))
        for (limits in listOf(DrivingInteraction.Unknown(), DrivingInteraction.Known(true),
            DrivingInteraction.Known(false, maximumTextLength = 100))) {
            assertNull(pending.afterRead(state, limits, snapshot, 7, 7, 200))
        }
        assertNull(HomeVisitCompletionConfirmation.fromObservation(state, VisitKind.PICKUP,
            DrivingInteraction.Unknown(), snapshot, 7, 7))
    }

    @Test fun aClockBeforeTheRecordedStartCannotAddACompletion() {
        val state = active()
        val snapshot = observed(checkNotNull(state.navigationWindow))
        val pending = checkNotNull(confirmation(state, snapshot))
        assertNull(pending.afterRead(state, editable, snapshot, 7, 7, 99))
        assertNull(pending.afterRead(state.copy(progress = state.progress.copy(startedAtMillis = null)), editable, snapshot, 7, 7, 200))
    }

    @Test fun anotherVisitOrAnUnstartedPlanCannotCreateTheDialog() {
        val state = active()
        val snapshot = observed(checkNotNull(state.navigationWindow))
        assertNull(HomeVisitCompletionConfirmation.fromObservation(state, VisitKind.GROCERIES, editable, snapshot, 7, 7))
        assertNull(confirmation(state.copy(progress = JourneyProgress()), snapshot))
    }

    @Test fun resumeApprovalIsBoundToTheFullItineraryAndTheDisplayResponse() {
        val state = active(addresses = true)
        val plan = checkNotNull(state.navigationWindow)
        val approval = checkNotNull(RouteChangeApproval.fromObservation(inactive, 7, 7))
        val pending = NavigationResumeConfirmation(plan, approval, state, editable)
        assertTrue(pending.isCurrent(state, editable, 7))
        assertFalse(pending.isCurrent(state.copy(draft = state.draft.copy(home = "다른 집")), editable, 7))
        assertFalse(pending.isCurrent(state, editable, 8))
        assertFalse(pending.isCurrent(state, DrivingInteraction.Known(false, maximumContentDepth = 2), 7))
    }
}
