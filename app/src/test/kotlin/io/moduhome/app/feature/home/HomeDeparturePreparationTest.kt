package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.PlanIssueKind
import io.moduhome.app.core.routing.*
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.data.JourneyJson
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HomeDeparturePreparationTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-10-29T09:00:00Z").toEpochMilli()
    private val origin = NavigationPoint("현재 차량 위치", 37.51, 127.01)
    private val home = NavigationPoint("집", 37.50, 127.0)
    private val pickup = NavigationPoint("픽업", 37.52, 127.02)
    private val grocery = NavigationPoint("장보기", 37.53, 127.03)

    private fun reviewed(at: Long = now, deadline: String = "18:30", nextDay: Boolean = false): HomeUiState {
        val date = Instant.ofEpochMilli(at).atZone(zone)
        val draft = JourneyDraft(origin = origin.name, home = home.name,
            departure = "%02d:%02d".format(date.hour, date.minute), dateEpochDay = date.toLocalDate().toEpochDay(),
            budget = 60, clockZoneId = zone.id,
            pickup = VisitDraft(place = pickup.name, stay = "1", ready = true, deadline = deadline, deadlineNextDay = nextDay),
            groceries = VisitDraft(place = grocery.name, stay = "0"), navigationPlaces = NavigationPlaces(home, pickup, grocery))
        fun estimate(points: List<NavigationPoint>, legs: List<Double>): RouteEstimate =
            (RouteEstimate.fromObservation(points, legs.map { ObservedRouteLeg(it, it * 10) },
                points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
                legs.sum(), legs.sum() * 10, at, "map-2026-10-29") as RouteEstimateResult.Success).estimate
        val direct = estimate(listOf(origin, home), listOf(300.0))
        val routes = mapOf("PICKUP>GROCERIES" to estimate(listOf(origin, pickup, grocery, home), listOf(60.0, 60.0, 180.0)),
            "GROCERIES>PICKUP" to estimate(listOf(origin, grocery, pickup, home), listOf(60.0, 60.0, 180.0)))
        val routing = requireNotNull(RoutedJourneyEstimates.create(draft, origin, direct, routes))
        val state = HomeUiState(draft, routing = routing)
        val progress = JourneyProgress().compare(state.plans).select("GROCERIES>PICKUP", state.plans).review(state.plans)
        assertEquals(JourneyStage.REVIEW, progress.stage)
        return state.copy(progress = progress)
    }

    @Test fun departureUsesCurrentTimeWithoutRefreshingAnyRoadObservation() {
        val old = reviewed()
        val result = HomeDepartureResult.prepare(old, now + 120_000, zone) as HomeDepartureResult.Ready
        assertEquals("18:02", result.state.draft.departure)
        assertEquals(old.selectedPlan!!.homeArrivalMinute + 2, result.plan.homeArrivalMinute)
        assertEquals(old.progress.selectedPlanId, result.plan.id)
        assertSame(old.routing, result.state.routing)
        assertSame(old.routing!!.direct, result.state.routing!!.direct)
        assertEquals(now, result.state.routing!!.queriedAtEpochMillis)
        assertEquals(old.routing!!.dataVersion, result.state.routing!!.dataVersion)
        assertFalse(result.state.routing!!.isFresh(now + RouteEstimate.FRESHNESS_MILLIS + 1))
        assertEquals(JourneyStage.REVIEW, result.state.progress.stage)
    }

    @Test fun waitingCanInvalidateTheSelectedOrderWhileAnotherOrderRemainsValid() {
        val old = reviewed(deadline = "18:04")
        assertTrue(old.selectedPlan!!.isValid)
        val result = HomeDepartureResult.prepare(old, now + 120_000, zone) as HomeDepartureResult.Recompare
        assertEquals(JourneyStage.COMPARE, result.state.progress.stage)
        assertEquals(old.progress.selectedPlanId, result.state.progress.selectedPlanId)
        assertFalse(result.state.selectedPlan!!.isValid)
        assertTrue(result.state.selectedPlan!!.issues.any { it.kind == PlanIssueKind.DEADLINE && it.minutes == 1 })
        assertTrue(result.state.plans.single { it.id == "PICKUP>GROCERIES" }.isValid)
        assertNull(result.state.progress.startedAtMillis)
    }

    @Test fun partialMinuteDepartureNeverUsesAnEarlierClockForDeadlineApproval() {
        val old = reviewed(deadline = "18:03")
        val result = HomeDepartureResult.prepare(old, now + 1, zone) as HomeDepartureResult.Recompare
        assertEquals("18:01", result.state.draft.departure)
        assertFalse(result.state.selectedPlan!!.isValid)
    }

    @Test fun expiredFutureDatedOrMismatchedRoadDataRequireAnotherQuery() {
        val old = reviewed()
        for (at in listOf(now - 1, now + RouteEstimate.FRESHNESS_MILLIS + 1)) {
            val result = HomeDepartureResult.prepare(old, at, zone) as HomeDepartureResult.Refresh
            assertEquals(JourneyProgress(), result.state.progress)
            assertNull(result.state.routing)
            assertEquals(old.draft, result.state.draft)
        }
        val changed = old.copy(draft = old.draft.copy(home = "바뀐 집"))
        assertTrue(HomeDepartureResult.prepare(changed, now, zone) is HomeDepartureResult.Refresh)
    }

    @Test fun theFreshnessBoundaryDoesNotGainAnotherFiveMinutesAfterRebasing() {
        val old = reviewed()
        val ready = HomeDepartureResult.prepare(old, now + RouteEstimate.FRESHNESS_MILLIS, zone) as HomeDepartureResult.Ready
        assertTrue(HomeDepartureResult.prepare(ready.state, now + RouteEstimate.FRESHNESS_MILLIS + 1, zone) is HomeDepartureResult.Refresh)
    }

    @Test fun midnightKeepsTheAbsoluteDeadlineDateAndChangesOnlyItsRelativeFlag() {
        val late = Instant.parse("2026-10-29T14:59:00Z").toEpochMilli()
        val old = reviewed(late, "00:10", true)
        val ready = HomeDepartureResult.prepare(old, late + 120_000, zone) as HomeDepartureResult.Ready
        assertEquals(old.draft.dateEpochDay + 1, ready.state.draft.dateEpochDay)
        assertEquals("00:01", ready.state.draft.departure)
        assertEquals("00:10", ready.state.draft.pickup.deadline)
        assertFalse(ready.state.draft.pickup.deadlineNextDay)
        assertEquals(old.draft.navigationPlaces, ready.state.draft.navigationPlaces)
        assertSame(old.routing, ready.state.routing)
    }

    @Test fun aDeadlineOnThePreviousDateCannotBeRolledForwardAfterMidnight() {
        val late = Instant.parse("2026-10-29T14:55:00Z").toEpochMilli()
        val old = reviewed(late, "23:59")
        assertTrue(HomeDepartureResult.prepare(old, late + 240_001, zone) is HomeDepartureResult.CalendarReview)
        assertEquals("23:59", old.draft.pickup.deadline)
        assertFalse(old.draft.pickup.deadlineNextDay)
    }

    @Test fun changedOrUnknownTimezonesRequireExplicitCalendarReviewWhenDeadlinesExist() {
        val old = reviewed()
        assertTrue(HomeDepartureResult.prepare(old, now, ZoneId.of("Asia/Tokyo")) is HomeDepartureResult.CalendarReview)
        assertTrue(HomeDepartureResult.prepare(old.copy(draft = old.draft.copy(clockZoneId = null)), now, zone) is HomeDepartureResult.CalendarReview)
    }

    @Test fun aLegacyDraftWithoutDeadlinesCanAdoptTodaysClockWithoutChangingPlaces() {
        val old = reviewed(deadline = "").draft.copy(clockZoneId = null, dateEpochDay = 0)
        val ready = DepartureDraftResult.prepare(old, now, zone) as DepartureDraftResult.Ready
        assertEquals(zone.id, ready.draft.clockZoneId)
        assertEquals("18:00", ready.draft.departure)
        assertEquals(old.navigationPlaces, ready.draft.navigationPlaces)
        assertEquals(old.pickup, ready.draft.pickup)
    }

    @Test fun invalidClockTextAndDisabledPastDeadlinesCannotBeReinterpretedSilently() {
        val input = reviewed().draft
        assertTrue(DepartureDraftResult.prepare(input.copy(pickup = input.pickup.copy(deadline = "99:90")), now, zone)
            is DepartureDraftResult.Invalid)
        val oldDay = input.copy(dateEpochDay = input.dateEpochDay - 1, groceries = input.groceries.copy(enabled = false, deadline = "20:00"))
        assertTrue(DepartureDraftResult.prepare(oldDay, now, zone) is DepartureDraftResult.CalendarReview)
    }

    @Test fun activeCompleteAndMissingRouteStatesCannotBePreparedForAnotherStart() {
        val old = reviewed()
        for (stage in JourneyStage.entries.filter { it != JourneyStage.REVIEW }) {
            assertTrue(HomeDepartureResult.prepare(old.copy(progress = old.progress.copy(stage = stage)), now, zone) is HomeDepartureResult.Rejected)
        }
        assertTrue(HomeDepartureResult.prepare(old.copy(routing = null), now, zone) is HomeDepartureResult.Rejected)
    }

    @Test fun rebaseAndRestorePreserveAcceptedProposalHistoryAndOriginalQueryTime() {
        val old = reviewed()
        val proposal = ErrandProposal("deadline-source", 1, now, now + 3_600_000, VisitKind.PICKUP, pickup, 1,
            ready = true, deadlineAtMillis = now + 1_800_000)
        val retained = old.copy(acceptedProposals = listOf(HomeProposalAcceptance(proposal, now)))
        val ready = HomeDepartureResult.prepare(retained, now + 120_000, zone) as HomeDepartureResult.Ready
        val restored = JourneyJson.decode(requireNotNull(JourneyJson.encode(ready.state)), old.draft, now + 120_001)
        assertEquals(retained.acceptedProposals, restored.acceptedProposals)
        assertEquals(zone.id, restored.draft.clockZoneId)
        assertEquals("18:02", restored.draft.departure)
        assertEquals(now, restored.routing!!.queriedAtEpochMillis)
    }

    @Test fun anExplicitNewJourneyKeepsPlacesButClearsOldDeadlinesAndReadiness() {
        val old = reviewed().draft.copy(groceries = reviewed().draft.groceries.copy(deadline = "20:00", deadlineNextDay = true))
        val next = old.newJourney(old.dateEpochDay + 1, "10:00", zone.id)
        assertEquals(old.navigationPlaces, next.navigationPlaces)
        assertEquals(old.pickup.stay, next.pickup.stay)
        assertEquals(old.groceries.stay, next.groceries.stay)
        assertEquals("", next.pickup.deadline)
        assertEquals("", next.groceries.deadline)
        assertFalse(next.pickup.ready)
        assertFalse(next.pickup.deadlineNextDay)
        assertFalse(next.groceries.deadlineNextDay)
    }
}
