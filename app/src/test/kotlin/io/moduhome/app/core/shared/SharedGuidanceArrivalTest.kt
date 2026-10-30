package io.moduhome.app.core.shared

import io.moduhome.app.core.navigation.GuidanceEstimate
import io.moduhome.app.core.navigation.GuidanceEstimateResult
import io.moduhome.app.core.navigation.GuidancePointObservation
import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.ObservedNavigationPlace
import io.moduhome.app.core.planning.ErrandVisit
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SharedGuidanceArrivalTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val departure = at("2026-10-30T19:17:00")
    private val home = point("home", 37.50)
    private val a = point("a", 37.51, "")
    private val b = point("b", 37.52)

    @Test fun usesCurrentNativeTimeAndIncludesThePassengerStopInTheDeadline() {
        val plan = plan(listOf(dropoff(a, "a", 1_185)), 1_179)
        val progress = progress(plan)
        val native = estimate(checkNotNull(progress.confirmedWindow), departure, 2_880)
        val value = project(plan, progress, native)!!
        assertEquals(a, value.point)
        assertEquals(at("2026-10-30T20:05:00"), value.drivingArrivalAtMillis)
        assertEquals(at("2026-10-30T20:06:00"), value.readyAtMillis)
        assertEquals(21L, value.lateMinutes)
        assertEquals(27L, value.planDelayMinutes)
        assertEquals(48L, value.drivingMinutes)
    }

    @Test fun selectsTheFirstWaypointWithoutAddingCumulativeMetricsTogether() {
        val first = a.copy(placeId = "a")
        val plan = plan(listOf(dropoff(first, "a"), dropoff(b, "b")))
        val progress = progress(plan)
        val native = estimate(checkNotNull(progress.confirmedWindow), departure, 1_500, listOf(300, 900))
        val value = project(plan, progress, native)!!
        assertEquals(first, value.point)
        assertEquals(departure + 6 * 60_000, value.readyAtMillis)
    }

    @Test fun completedStopTimeIsNotAddedAgainAndOldWindowCannotLabelTheNextPassenger() {
        val first = a.copy(placeId = "a")
        val plan = plan(listOf(dropoff(first, "a"), dropoff(b, "b")))
        val before = progress(plan)
        val old = estimate(checkNotNull(before.confirmedWindow), departure, 1_500, listOf(300, 900))
        val pending = before.confirmStop(plan, departure + 1_000)
        assertNull(project(plan, pending, old, departure + 1_000))
        val window = checkNotNull(pending.pendingWindow)
        val completed = pending.observeNavigation(snapshot(window))
        val fresh = estimate(window, departure + 2_000, 1_200, listOf(600))
        assertNull(project(plan, completed, old, departure + 2_000))
        assertEquals(departure + 2_000 + 11 * 60_000, project(plan, completed, fresh, departure + 2_000)!!.readyAtMillis)
    }

    @Test fun aNativePassedWaypointNeverBecomesAnAutomaticPhysicalCompletion() {
        val first = a.copy(placeId = "a")
        val plan = plan(listOf(dropoff(first, "a"), dropoff(b, "b")))
        val progress = progress(plan)
        val window = checkNotNull(progress.confirmedWindow)
        val suffix = snapshot(window).copy(waypoints = listOf(observed(b)))
        val native = estimate(window, departure, 1_200, listOf(600), suffix)
        assertNull(project(plan, progress, native))
        assertEquals(0, progress.completedStopCount)
    }

    @Test fun errandDeadlineUsesWalkingToTheShopAndServiceButNotTheReturnWalk() {
        val shop = point("shop", 37.53, "")
        val errand = SharedScheduledStop.Errand(
            SharedErrand(SharedJourneyPlace("shop", shop), ErrandVisit(VisitKind.PICKUP, "shop", 5,
                walkingMinutesOneWay = 2, deadlineMinute = 1_175)), 1_168, 1_175, 1_177,
        )
        val plan = plan(listOf(errand))
        val progress = progress(plan)
        val native = estimate(checkNotNull(progress.confirmedWindow), departure, 600)
        val value = project(plan, progress, native)!!
        assertEquals(departure + 17 * 60_000, value.readyAtMillis)
        assertEquals(0L, value.lateMinutes)
    }

    @Test fun midnightDeadlineKeepsTheJourneyDate() {
        val received = at("2026-10-30T23:59:00")
        val plan = plan(listOf(dropoff(a, "a", 1_440)), 1_441)
        val progress = progress(plan, received)
        val native = estimate(checkNotNull(progress.confirmedWindow), received, 60)
        val value = SharedGuidanceArrival.fromObservation(plan, progress, native, received, zone, received)!!
        assertEquals(at("2026-10-31T00:01:00"), value.readyAtMillis)
        assertEquals(1L, value.lateMinutes)
    }

    @Test fun homeIsShownOnlyAfterAllStopsAreExplicitlyCompleted() {
        val plan = plan(listOf(dropoff(a, "a")))
        val pending = progress(plan).confirmStop(plan, departure + 1_000)
        val window = checkNotNull(pending.pendingWindow)
        val progress = pending.observeNavigation(snapshot(window))
        val native = estimate(window, departure + 2_000, 900)
        val value = project(plan, progress, native, departure + 2_000)!!
        assertEquals(home, value.point)
        assertEquals(departure + 2_000 + 15 * 60_000, value.readyAtMillis)
        assertNull(value.deadlineAtMillis)
    }

    @Test fun staleFutureDifferentAndCompletedObservationsCannotProduceAnArrival() {
        val plan = plan(listOf(dropoff(a, "a")))
        val progress = progress(plan)
        val native = estimate(checkNotNull(progress.confirmedWindow), departure, 60)
        assertNull(project(plan, progress, native, departure + GuidanceEstimate.FRESHNESS_MILLIS + 1))
        assertNull(project(plan, progress, native, departure - 1))
        val other = estimate(NavigationPlan(b), departure, 60)
        assertNull(project(plan, progress, other))
        assertNull(project(plan, progress.copy(startedAtMillis = departure + 1), native))
    }

    @Test fun aDeadlineInADaylightSavingGapOrOverlapIsNotGuessed() {
        val localZone = ZoneId.of("America/New_York")
        for ((date, minute) in listOf("2026-03-08T00:00:00" to 150, "2026-11-01T00:00:00" to 90)) {
            val received = LocalDateTime.parse(date).atZone(localZone).toInstant().toEpochMilli()
            val plan = plan(listOf(dropoff(a, "a", minute)))
            val progress = progress(plan, received)
            val native = estimate(checkNotNull(progress.confirmedWindow), received, 60)
            assertNull(SharedGuidanceArrival.fromObservation(plan, progress, native, received, localZone, received))
        }
    }

    @Test fun anOverflowingArrivalDoesNotWrapAroundToAnEarlierTime() {
        val plan = plan(listOf(dropoff(a, "a")))
        val progress = progress(plan, Long.MAX_VALUE)
        val native = estimate(checkNotNull(progress.confirmedWindow), Long.MAX_VALUE, 60)
        assertNull(SharedGuidanceArrival.fromObservation(plan, progress, native, Long.MAX_VALUE, zone, Long.MAX_VALUE))
    }

    @Test fun partialMinuteLatenessRoundsUpAndARefreshClockDoesNotMoveTheSameEstimate() {
        val plan = plan(listOf(dropoff(a, "a", 1_187)))
        val progress = progress(plan)
        val native = estimate(checkNotNull(progress.confirmedWindow), departure + 10_000, 1_750)
        val first = project(plan, progress, native, departure + 10_000)!!
        val later = project(plan, progress, native, departure + 40_000)!!
        assertEquals(at("2026-10-30T19:47:20"), first.readyAtMillis)
        assertEquals(1L, first.lateMinutes)
        assertEquals(first.readyAtMillis, later.readyAtMillis)
        assertEquals(30L, later.drivingMinutes)
    }

    private fun plan(stops: List<SharedScheduledStop>, plannedArrival: Int = 1_179) = SharedJourneyPlan(
        "fixture", stops, stops.filterIsInstance<SharedScheduledStop.Dropoff>().map {
            SharedPassengerArrival(it.passenger.id, it.passenger.name, it.candidate, plannedArrival, plannedArrival, 10)
        }, 1_206, 30, 2, 10, 50, emptyList(), emptyList(),
    )

    private fun dropoff(point: NavigationPoint, id: String, deadline: Int? = null): SharedScheduledStop.Dropoff {
        val candidate = SharedDropoffCandidate(id, SharedJourneyPlace(id, point),
            OnwardJourneyEstimate(OnwardTravelMode.WALK, 0, 0, evidence = TravelEvidence(TravelEstimateKind.USER_INPUT, "fixture")))
        return SharedScheduledStop.Dropoff(SharedPassenger(id, id, listOf(candidate), deadline), candidate, 1_178, 1_179)
    }

    private fun progress(plan: SharedJourneyPlan, started: Long = departure): SharedJourneyProgress {
        val pending = SharedJourneyProgress().compare(listOf(plan)).review(listOf(plan)).start(plan, home, started)
        return pending.observeNavigation(snapshot(checkNotNull(pending.pendingWindow)))
    }

    private fun estimate(window: NavigationPlan, received: Long, seconds: Int, via: List<Int> = emptyList(),
                         route: NavigationSnapshot = snapshot(window)): GuidanceEstimate {
        val points = window.waypoints.takeLast(route.waypoints.size)
        return (GuidanceEstimate.fromObservation(window,
            GuidancePointObservation(route.destination, seconds, seconds * 10),
            points.mapIndexed { index, p -> GuidancePointObservation(observed(p), via[index], via[index] * 10) },
            route, route, received - 1, received) as GuidanceEstimateResult.Success).estimate
    }

    private fun project(plan: SharedJourneyPlan, progress: SharedJourneyProgress, native: GuidanceEstimate,
                        now: Long = departure) = SharedGuidanceArrival.fromObservation(plan, progress, native, departure, zone, now)
    private fun snapshot(plan: NavigationPlan) = NavigationSnapshot(observed(plan.destination), plan.waypoints.map(::observed), true)
    private fun observed(p: NavigationPoint) = ObservedNavigationPlace(p.name, p.placeId, p.provider, p.latitude, p.longitude, p.subId)
    private fun point(id: String, latitude: Double, poi: String = id) = NavigationPoint(id, latitude, 127.0, placeId = poi)
    private fun at(value: String): Long = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
}
