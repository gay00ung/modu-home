package io.moduhome.app.feature.vehicle

import io.moduhome.app.core.model.JourneyMode
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.ObservedRouteLeg
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.routing.RouteEstimateResult
import io.moduhome.app.core.routing.SnappedRoutePoint
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.SharedJourneyProgress
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyDraft
import io.moduhome.app.feature.home.NavigationPlaces
import io.moduhome.app.feature.home.VisitDraft
import io.moduhome.app.feature.shared.SharedErrandDraft
import io.moduhome.app.feature.shared.SharedJourneyDraft
import io.moduhome.app.feature.shared.SharedJourneySession
import io.moduhome.app.feature.shared.SharedPassengerDraft
import io.moduhome.app.feature.shared.SharedRoutedJourneyLoadResult
import io.moduhome.app.feature.shared.loadSharedRoutedJourney
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class JourneyShortcutStateTest {
    private val home = NavigationPoint("집", 37.50, 127.00, placeId = "home")
    private val pickup = NavigationPoint("픽업", 37.51, 127.01, placeId = "pickup")
    private val groceries = NavigationPoint("장보기", 37.52, 127.02, placeId = "groceries")
    private val now = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()

    private fun solo(): HomeUiState {
        val state = HomeUiState(JourneyDraft(home = home.name, budget = 60,
            pickup = VisitDraft(place = pickup.name, stay = "5", ready = true),
            groceries = VisitDraft(place = groceries.name, stay = "15"),
            navigationPlaces = NavigationPlaces(home, pickup, groceries)))
        val plan = state.plans.single { it.visits.first().visit.kind == VisitKind.PICKUP }
        return state.copy(progress = JourneyProgress().compare(state.plans).select(plan.id, state.plans)
            .review(state.plans).start(state.plans, now))
    }

    private fun together(): SharedJourneySession {
        val checked = DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "사용자 확인", now - 500)
        val draft = SharedJourneyDraft(home, listOf(
            SharedPassengerDraft("p1", "동승자 1", pickup, true, now - 1_000, checked),
            SharedPassengerDraft("p2", "동승자 2", groceries, true, now - 1_000, checked),
        ), SharedErrandDraft(VisitKind.PICKUP,
            NavigationPoint("볼일", 37.53, 127.03, placeId = "errand"), ready = true))
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult =
                RouteEstimate.fromObservation(points,
                    List(points.size - 1) { ObservedRouteLeg(60.0, 600.0) },
                    points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
                    (points.size - 1) * 60.0, (points.size - 1) * 600.0, now, "test-map")
        }
        val routing = (runBlocking {
            loadSharedRoutedJourney(draft, home, gateway, { now }, ZoneId.of("Asia/Seoul"))
        } as SharedRoutedJourneyLoadResult.Success).estimates
        val progress = SharedJourneyProgress().compare(routing.plans).review(routing.plans)
            .start(routing.plans.first(), home, now)
        return SharedJourneySession(draft, progress, routing)
    }

    @Test fun soloShortcutStaysAvailableUntilTheFinalHomeConfirmation() {
        val state = solo()
        val initial = checkNotNull(state.journeyShortcut())
        assertEquals(JourneyMode.SOLO, initial.request.mode)
        assertEquals(2, initial.remainingStops)
        val afterPickup = state.copy(progress = state.progress.copy(completedVisits = 1))
        assertEquals(1, afterPickup.journeyShortcut()?.remainingStops)
        val homeLeg = state.copy(progress = state.progress.copy(completedVisits = 2))
        assertEquals(0, homeLeg.journeyShortcut()?.remainingStops)
        assertEquals(initial.request, homeLeg.journeyShortcut()?.request)
        assertEquals(0, state.progress.completedVisits)
    }

    @Test fun soloInputReviewAndCompletedPlansLeaveNoReturnEntry() {
        val state = solo()
        for (stage in JourneyStage.entries.filter { it != JourneyStage.ACTIVE }) {
            assertNull(state.copy(progress = state.progress.copy(stage = stage)).journeyShortcut())
        }
    }

    @Test fun soloUnrestorableProgressDoesNotCreateAnEntry() {
        val state = solo()
        val malformed = listOf(
            state.progress.copy(startedAtMillis = null),
            state.progress.copy(startedAtMillis = -1),
            state.progress.copy(selectedPlanId = "missing"),
            state.progress.copy(completedVisits = -1),
            state.progress.copy(completedVisits = 3),
        )
        malformed.forEach { assertNull(state.copy(progress = it).journeyShortcut()) }
    }

    @Test fun soloInvalidInputDoesNotAdvertiseAnOldSelection() {
        val state = solo()
        assertNull(state.copy(draft = state.draft.copy(budget = 0)).journeyShortcut())
    }

    @Test fun sharedShortcutKeepsTheHomeLegAfterThreeRecordedStops() {
        val state = together()
        val initial = checkNotNull(state.journeyShortcut())
        assertEquals(JourneyMode.TOGETHER, initial.request.mode)
        assertEquals(3, initial.remainingStops)
        val homeLeg = state.copy(progress = state.progress.copy(
            completedStopCount = 3, completedAtMillis = listOf(now + 1, now + 2, now + 3)))
        assertEquals(0, homeLeg.journeyShortcut()?.remainingStops)
        assertEquals(initial.request, homeLeg.journeyShortcut()?.request)
        assertEquals(0, state.progress.completedStopCount)
    }

    @Test fun sharedNonActiveOrUnselectedPlansLeaveNoEntry() {
        val state = together()
        for (stage in JourneyStage.entries.filter { it != JourneyStage.ACTIVE }) {
            assertNull(state.copy(progress = state.progress.copy(stage = stage)).journeyShortcut())
        }
        assertNull(state.copy(progress = state.progress.copy(startedAtMillis = null)).journeyShortcut())
        assertNull(state.copy(progress = state.progress.copy(selectedPlanId = "missing")).journeyShortcut())
    }

    @Test fun sharedMissingOrMismatchedRoutesDoNotAdvertiseAnOldSelection() {
        val state = together()
        assertNull(state.copy(routing = null).journeyShortcut())
        val changed = state.draft.copy(driverHome = home.copy(latitude = home.latitude + .01))
        assertNull(state.copy(draft = changed).journeyShortcut())
    }
}
