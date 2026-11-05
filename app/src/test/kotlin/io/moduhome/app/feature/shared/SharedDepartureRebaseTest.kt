package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.ObservedRouteLeg
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.routing.RouteEstimateResult
import io.moduhome.app.core.routing.SnappedRoutePoint
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.SharedPlanIssue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SharedDepartureRebaseTest {
    private val now = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val origin = NavigationPoint("현재 차량", 37.50, 127.0)
    private val home = NavigationPoint("운전자 집", 37.51, 127.01)
    private val passengerHome = NavigationPoint("동승자 집", 37.52, 127.02)
    private val shop = NavigationPoint("픽업", 37.53, 127.03)

    private fun draft(at: Long = now): SharedJourneyDraft = SharedJourneyDraft(home, listOf(
        SharedPassengerDraft("p1", "동승자 1", passengerHome,
            DropoffVerification(AccessCheck.SUITABLE, "사용자 확인", at - 500)),
    ))

    private inner class Gateway(private val queriedAt: Long) : RouteEstimateGateway {
        var queries = 0
        override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult {
            queries += 1
            return RouteEstimate.fromObservation(points, List(points.size - 1) { ObservedRouteLeg(60.0, 600.0) },
                points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
                (points.size - 1) * 60.0, (points.size - 1) * 600.0, queriedAt, "test-map")
        }
    }

    private fun load(input: SharedJourneyDraft = draft(), at: Long = now, gateway: Gateway = Gateway(at)) =
        (runBlocking { loadSharedRoutedJourney(input, origin, gateway, { at }, zone) }
            as SharedRoutedJourneyLoadResult.Success).estimates

    @Test fun rebasingUsesTheNewDepartureWithoutIssuingOrRelabellingAQuery() {
        val gateway = Gateway(now)
        val original = load(gateway = gateway)
        val queries = gateway.queries
        val rebased = requireNotNull(original.rebaseDeparture(now + 2 * 60 * 1000))
        assertEquals(queries, gateway.queries)
        assertEquals(now, rebased.queriedAtEpochMillis)
        assertEquals(original.source, rebased.source)
        assertEquals(original.dataVersion, rebased.dataVersion)
        assertSame(original.direct, rebased.direct)
        original.windows.forEach { (id, windows) -> windows.forEachIndexed { index, window ->
            assertSame(window.route, rebased.windows.getValue(id)[index].route)
        } }
        assertEquals(now + 2 * 60 * 1000, rebased.departureAtMillis)
        assertEquals(1082, rebased.request.departureMinute)
        assertEquals(original.plans.single().driverHomeArrivalMinute?.plus(2), rebased.plans.single().driverHomeArrivalMinute)
    }

    @Test fun aLaterDepartureCanMakeTheSameSelectedOrderMissThePassengersDeadline() {
        val input = draft().copy(passengers = draft().passengers.map { it.copy(latestArrivalAtMillis = now + 60 * 1000) })
        val original = load(input)
        assertTrue(original.plans.single().isValid)
        val rebased = requireNotNull(original.rebaseDeparture(now + 60 * 1000))
        assertEquals(original.plans.single().id, rebased.plans.single().id)
        assertFalse(rebased.plans.single().isValid)
        assertTrue(rebased.plans.single().issues.contains(SharedPlanIssue.PassengerLate("p1", 1)))
    }

    @Test fun anAlreadyElapsedAbsoluteDeadlineRequiresTheInputConditionsToBeReviewed() {
        val input = draft().copy(passengers = draft().passengers.map { it.copy(latestArrivalAtMillis = now + 2 * 60 * 1000) })
        val original = load(input)
        val delayed = now + 3 * 60 * 1000
        assertTrue(original.isFresh(delayed))
        assertNull(original.rebaseDeparture(delayed))
    }

    @Test fun errandClosingTimeIsRecheckedAgainstTheNewDepartureToo() {
        val input = draft().copy(errand = SharedErrandDraft(VisitKind.PICKUP, shop, stayMinutes = 0,
            ready = true, deadlineAtMillis = now + 60 * 1000))
        val original = load(input)
        val selected = original.plans.single { it.stops.first().place.point == shop }
        assertTrue(selected.isValid)
        val rebased = requireNotNull(original.rebaseDeparture(now + 60 * 1000))
        val delayed = rebased.plans.single { it.id == selected.id }
        assertFalse(delayed.isValid)
        assertTrue(delayed.issues.contains(SharedPlanIssue.ErrandDeadline(1)))
    }

    @Test fun crossingMidnightRebasesBothDepartureAndAnExplicitNextDayDeadline() {
        val late = Instant.parse("2026-10-28T14:59:00Z").toEpochMilli()
        val deadline = Instant.parse("2026-10-28T15:05:00Z").toEpochMilli()
        val input = draft(late).copy(passengers = draft(late).passengers.map { it.copy(latestArrivalAtMillis = deadline) })
        val original = load(input, at = late)
        assertEquals(1439, original.request.departureMinute)
        assertEquals(1445, original.request.passengers.single().latestArrivalMinute)
        val rebased = requireNotNull(original.rebaseDeparture(late + 2 * 60 * 1000))
        assertEquals(zone, rebased.zoneId)
        assertEquals(1, rebased.request.departureMinute)
        assertEquals(5, rebased.request.passengers.single().latestArrivalMinute)
        assertEquals(2, rebased.plans.single().passengerArrivals.single().dropoffMinute)
        assertTrue(rebased.plans.single().isValid)
    }

    @Test fun allThreeStopOrdersRetainTheirIdentityAndStopSequence() {
        val first = draft().passengers.single()
        val input = draft().copy(passengers = listOf(first, first.copy(id = "p2", name = "동승자 2",
            dropoff = passengerHome.copy(placeId = "second-home"))),
            errand = SharedErrandDraft(VisitKind.PICKUP, shop, ready = true))
        val original = load(input)
        val rebased = requireNotNull(original.rebaseDeparture(now + 2 * 60 * 1000))
        assertEquals(6, rebased.plans.size)
        assertEquals(original.plans.map { it.id }.toSet(), rebased.plans.map { it.id }.toSet())
        original.plans.forEach { plan ->
            val updated = rebased.plans.single { it.id == plan.id }
            assertEquals(plan.stops.map { it.place }, updated.stops.map { it.place })
            assertEquals(plan.driverHomeArrivalMinute?.plus(2), updated.driverHomeArrivalMinute)
            assertEquals(plan.drivingMinutes, updated.drivingMinutes)
        }
    }

    @Test fun freshnessBoundaryIsInclusiveButRebasingNeverExtendsTheOriginalQueryLifetime() {
        val original = load()
        val rebased = requireNotNull(original.rebaseDeparture(now + RouteEstimate.FRESHNESS_MILLIS))
        assertEquals(now, rebased.queriedAtEpochMillis)
        assertNull(original.rebaseDeparture(now + RouteEstimate.FRESHNESS_MILLIS + 1))
        assertNull(rebased.rebaseDeparture(now + RouteEstimate.FRESHNESS_MILLIS + 1))
    }

    @Test fun aClockMovingBehindTheLastValidationCannotMoveDepartureBackwards() {
        val original = load()
        assertNull(original.rebaseDeparture(now - 1))
        val rebased = requireNotNull(original.rebaseDeparture(now + 2 * 60 * 1000))
        assertNull(rebased.rebaseDeparture(now + 60 * 1000))
    }
}
