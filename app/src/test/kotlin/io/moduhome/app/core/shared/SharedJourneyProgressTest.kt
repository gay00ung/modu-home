package io.moduhome.app.core.shared

import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.ObservedNavigationPlace
import io.moduhome.app.core.planning.ErrandVisit
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedJourneyProgressTest {
    private val home = point("home", 37.50)
    private val stops = listOf(point("a", 37.51), point("b", 37.52), point("c", 37.53))
    private val plan = SharedJourneyPlan(
        id = "a>b>c", stops = stops.map { stop ->
            val errand = SharedErrand(SharedJourneyPlace(stop.placeId, stop), ErrandVisit(VisitKind.PICKUP, stop.name, 1))
            SharedScheduledStop.Errand(errand, 100, 101, 101)
        }, passengerArrivals = emptyList(), driverHomeArrivalMinute = 120, drivingMinutes = 20,
        stopMinutes = 3, driverAdditionalMinutes = 10, totalPersonMinutes = 20,
        evidence = emptyList(), issues = emptyList(),
    )

    @Test fun keepsFullPlanAndHomeWhileSlidingTheTwoStopWindow() {
        assertEquals(NavigationPlan(home, stops.take(2)), plan.currentWindow(home, 0))
        assertEquals(NavigationPlan(home, stops.drop(1)), plan.currentWindow(home, 1))
        assertEquals(NavigationPlan(home, stops.takeLast(1)), plan.currentWindow(home, 2))
        assertEquals(NavigationPlan(home), plan.currentWindow(home, 3))
        assertEquals(stops, plan.stops.map { it.place.point })
        assertThrows(IllegalArgumentException::class.java) { plan.currentWindow(home, -1) }
        assertThrows(IllegalArgumentException::class.java) { plan.currentWindow(home, 4) }
        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(stops = plan.stops + plan.stops.first()).currentWindow(home, 0)
        }
    }

    @Test fun startsPendingAndRequiresTheVehicleReadbackBeforeCompletingAStop() {
        val started = start()
        assertEquals(JourneyStage.ACTIVE, started.stage)
        assertEquals(1_000L, started.startedAtMillis)
        assertTrue(started.navigationPending)
        assertSame(started, started.confirmStop(plan, 2_000))
        val confirmed = started.observeNavigation(snapshot(started.pendingWindow!!))
        assertFalse(confirmed.navigationPending)
        assertEquals(started.pendingWindow, confirmed.confirmedWindow)
    }

    @Test fun firstCompletionReplacesTheWindowWithSecondAndThirdStops() {
        val confirmed = confirm(start())
        val completed = confirmed.confirmStop(plan, 2_000)
        assertEquals(1, completed.completedStopCount)
        assertEquals(listOf(2_000L), completed.completedAtMillis)
        assertEquals(NavigationPlan(home, listOf(stops[1], stops[2])), completed.pendingWindow)
        assertEquals(NavigationPlan(home, stops.take(2)), completed.confirmedWindow)
        assertSame(completed, completed.confirmStop(plan, 2_001))
    }

    @Test fun aFailedSendAndRetryNeverRollBackPhysicalCompletion() {
        val completed = confirm(start()).confirmStop(plan, 2_000)
        val retry = completed.requestNavigation(plan, home)
        assertEquals(completed, retry)
        val previous = snapshot(completed.confirmedWindow!!)
        assertSame(retry, retry.observeNavigation(previous))
        assertEquals(1, retry.completedStopCount)
        val recovered = confirm(retry)
        assertEquals(listOf(2_000L), recovered.completedAtMillis)
        assertFalse(recovered.navigationPending)
    }

    @Test fun rejectsExternalWrongOrderInactiveAndMissingReadbacks() {
        val pending = confirm(start()).confirmStop(plan, 2_000)
        val target = pending.pendingWindow!!
        for (observed in listOf(null, snapshot(target).copy(guidanceActive = false),
            snapshot(target.copy(destination = stops[0])),
            snapshot(target.copy(waypoints = target.waypoints.reversed())),
            snapshot(target.copy(waypoints = emptyList())))) {
            assertSame(pending, pending.observeNavigation(observed))
        }
        assertTrue(pending.navigationPending)
    }

    @Test fun allStopsCompletedKeepsTheHomeRouteForExplicitArrivalConfirmation() {
        var progress = confirm(start())
        for (index in stops.indices) {
            progress = progress.confirmStop(plan, 2_000L + index)
            assertEquals(index + 1, progress.completedStopCount)
            if (index < stops.lastIndex) assertSame(progress, progress.finish(plan, 3_000))
            progress = confirm(progress)
        }
        assertEquals(NavigationPlan(home), progress.confirmedWindow)
        assertEquals(listOf(2_000L, 2_001L, 2_002L), progress.completedAtMillis)
        assertSame(progress, progress.confirmStop(plan, 4_000))
        assertEquals(JourneyStage.COMPLETE, progress.finish(plan, 4_000).stage)
    }

    @Test fun manualHomeArrivalWorksAfterNativeGuidanceEndsWithoutInventingAConfirmation() {
        val atHome = allStopsRecorded()
        assertTrue(atHome.navigationPending)
        assertEquals(NavigationPlan(home), atHome.pendingWindow)
        val nativeEnded = snapshot(NavigationPlan(home)).copy(guidanceActive = false)
        assertSame(atHome, atHome.observeNavigation(nativeEnded))
        val finished = atHome.finish(plan, 4_000)
        assertEquals(JourneyStage.COMPLETE, finished.stage)
        assertEquals(atHome.confirmedWindow, finished.confirmedWindow)
        assertEquals(atHome.pendingWindow, finished.pendingWindow)
        assertEquals(atHome.completedAtMillis, finished.completedAtMillis)
        assertFalse(finished.navigationPending)
    }

    @Test fun explicitArrivalAfterRestartDoesNotRequireNativeGuidanceToRestart() {
        val restarted = allStopsRecorded().afterRestart(plan, home)
        assertEquals(null, restarted.confirmedWindow)
        assertEquals(NavigationPlan(home), restarted.pendingWindow)
        val finished = restarted.finish(plan, 4_000)
        assertEquals(JourneyStage.COMPLETE, finished.stage)
        assertEquals(null, finished.confirmedWindow)
        assertFalse(finished.navigationPending)
        assertSame(finished, finished.requestNavigation(plan, home))
    }

    @Test fun manualArrivalStillRejectsIncompleteStopsUnknownHistoryAndInvalidTime() {
        val confirmed = confirm(start())
        assertSame(confirmed, confirmed.finish(plan, 4_000))
        val pending = confirmed.confirmStop(plan, 2_000)
        assertSame(pending, pending.finish(plan, 4_000))
        val atHome = allStopsRecorded()
        val unknownWindow = atHome.copy(confirmedWindow = null, pendingWindow = null)
        assertSame(unknownWindow, unknownWindow.finish(plan, 4_000))
        val unknownStart = atHome.copy(startedAtMillis = null)
        assertSame(unknownStart, unknownStart.finish(plan, 4_000))
        assertThrows(IllegalArgumentException::class.java) { atHome.finish(plan, 2_001) }
    }

    @Test fun restartMakesOldConfirmationPendingWithoutResettingCompletedStops() {
        val prior = confirm(confirm(start()).confirmStop(plan, 2_000))
        val restarted = prior.afterRestart(plan, home)
        assertEquals(prior.completedAtMillis, restarted.completedAtMillis)
        assertEquals(prior.startedAtMillis, restarted.startedAtMillis)
        assertEquals(1, restarted.completedStopCount)
        assertEquals(null, restarted.confirmedWindow)
        assertTrue(restarted.navigationPending)
        assertSame(restarted, restarted.confirmStop(plan, 3_000))
        // A readback can recover a route already accepted before process death; it never sends one.
        assertFalse(confirm(restarted).navigationPending)
    }

    @Test fun anotherSelectedPlanOrHomeCannotReplaceTheActiveJourney() {
        val progress = confirm(start())
        assertSame(progress, progress.confirmStop(plan.copy(id = "another"), 2_000))
        assertSame(progress, progress.requestNavigation(plan, stops[0]))
        assertSame(progress, progress.afterRestart(plan, stops[0]))
        assertSame(progress, progress.start(plan, home, 3_000))
    }

    @Test fun completionTimesCannotMoveBackwardsOrLoseEntries() {
        assertThrows(IllegalArgumentException::class.java) { confirm(start()).confirmStop(plan, 999) }
        assertThrows(IllegalArgumentException::class.java) { SharedJourneyProgress(completedStopCount = 1) }
        assertThrows(IllegalArgumentException::class.java) {
            SharedJourneyProgress(completedStopCount = 2, completedAtMillis = listOf(2_000, 1_000))
        }
    }

    @Test fun aMatchingReadbackNeverMarksAPassengerOrErrandCompleted() {
        val started = start()
        val recovered = confirm(started)
        assertEquals(0, recovered.completedStopCount)
        assertTrue(recovered.completedAtMillis.isEmpty())
        assertEquals(JourneyStage.ACTIVE, recovered.stage)
        assertSame(recovered, recovered.observeNavigation(snapshot(NavigationPlan(home))))
    }

    @Test fun anExternalRouteRequiresTheObservingFlowToExplicitlyMarkNavigationPending() {
        val confirmed = confirm(start())
        val external = snapshot(NavigationPlan(stops[2]))
        assertSame(confirmed, confirmed.observeNavigation(external))
        val marked = confirmed.requestNavigation(plan, home)
        assertTrue(marked.navigationPending)
        assertEquals(confirmed.confirmedWindow, marked.confirmedWindow)
        assertEquals(confirmed.completedAtMillis, marked.completedAtMillis)
        assertSame(marked, marked.observeNavigation(external))
        assertSame(marked, marked.confirmStop(plan, 2_000))
        assertFalse(confirm(marked).navigationPending)
    }

    @Test fun aPendingCompletedStopCannotBeCountedAgainAfterRestartOrRetry() {
        val completed = confirm(start()).confirmStop(plan, 2_000)
        val restarted = completed.afterRestart(plan, home).requestNavigation(plan, home)
        assertSame(restarted, restarted.confirmStop(plan, 2_001))
        assertSame(restarted, restarted.confirmStop(plan, 2_002))
        assertEquals(1, restarted.completedStopCount)
        assertEquals(listOf(2_000L), restarted.completedAtMillis)
    }

    @Test fun aDifferentSelectedPlanCannotChangePendingOrConfirmedProgress() {
        val other = plan.copy(id = "another")
        for (progress in listOf(start(), confirm(start()), confirm(start()).confirmStop(plan, 2_000))) {
            assertSame(progress, progress.confirmStop(other, 3_000))
            assertSame(progress, progress.requestNavigation(other, home))
            assertSame(progress, progress.afterRestart(other, home))
            assertSame(progress, progress.finish(other, 3_000))
        }
    }

    @Test fun anInconsistentOrInvalidPlanCannotUseAnOldWindowAsConfirmation() {
        val progress = confirm(start())
        val reversed = plan.copy(stops = plan.stops.reversed())
        val invalid = plan.copy(issues = listOf(SharedPlanIssue.ErrandNotReady))
        for (other in listOf(reversed, invalid)) {
            assertSame(progress, progress.confirmStop(other, 2_000))
            assertSame(progress, progress.requestNavigation(other, home))
            assertSame(progress, progress.afterRestart(other, home))
        }
    }

    @Test fun aCompletedJourneyCannotBeReopenedOrChangedByNavigationObservations() {
        var progress = confirm(start())
        for (index in stops.indices) progress = confirm(progress.confirmStop(plan, 2_000L + index))
        val finished = progress.finish(plan, 4_000)
        assertEquals(JourneyStage.COMPLETE, finished.stage)
        assertSame(finished, finished.compare(listOf(plan)))
        assertSame(finished, finished.select(plan.id, listOf(plan)))
        assertSame(finished, finished.review(listOf(plan)))
        assertSame(finished, finished.start(plan, home, 5_000))
        assertSame(finished, finished.confirmStop(plan, 5_000))
        assertSame(finished, finished.requestNavigation(plan, home))
        assertSame(finished, finished.afterRestart(plan, home))
        assertSame(finished, finished.observeNavigation(null))
        assertSame(finished, finished.finish(plan, 5_000))
    }

    @Test fun explicitPassedStopConfirmationUsesOnlyTheActuallyObservedRemainder() {
        val initial = start()
        val consumedFirst = snapshot(NavigationPlan(home, listOf(stops[1])))
        assertSame(initial, initial.observeNavigation(consumedFirst))
        val recovered = initial.confirmPassedStop(plan, consumedFirst, 2_000)
        assertEquals(1, recovered.completedStopCount)
        assertEquals(listOf(2_000L), recovered.completedAtMillis)
        assertEquals(NavigationPlan(home, listOf(stops[1])), recovered.confirmedWindow)
        assertEquals(NavigationPlan(home, listOf(stops[1], stops[2])), recovered.pendingWindow)
        assertTrue(recovered.navigationPending)
        assertSame(recovered, recovered.confirmPassedStop(plan, consumedFirst, 2_001))
    }

    @Test fun passedStopCanBeExplicitlyConfirmedAfterRestartWithoutConfirmingTheOldFullWindow() {
        val restarted = confirm(start()).afterRestart(plan, home)
        assertEquals(null, restarted.confirmedWindow)
        val consumedFirst = snapshot(NavigationPlan(home, listOf(stops[1])))
        val recovered = restarted.confirmPassedStop(plan, consumedFirst, 2_000)
        assertEquals(1, recovered.completedStopCount)
        assertEquals(NavigationPlan(home, listOf(stops[1])), recovered.confirmedWindow)
        assertEquals(NavigationPlan(home, listOf(stops[1], stops[2])), recovered.pendingWindow)
    }

    @Test fun passedStopRejectsForeignInactiveUnconsumedAndOverConsumedRoutes() {
        val pending = start()
        val remaining = NavigationPlan(home, listOf(stops[1]))
        for (readback in listOf(
            snapshot(remaining.copy(destination = stops[2])),
            snapshot(remaining).copy(guidanceActive = false),
            snapshot(pending.pendingWindow!!),
            snapshot(NavigationPlan(home)),
            snapshot(NavigationPlan(home, listOf(stops[2]))),
        )) {
            assertSame(pending, pending.confirmPassedStop(plan, readback, 2_000))
        }
        assertSame(pending, pending.confirmPassedStop(plan.copy(id = "another"), snapshot(remaining), 2_000))
    }

    @Test fun passedStopRequiresPendingAndCannotCompleteTheHomeRouteOrCompletedJourney() {
        val confirmed = confirm(start())
        val consumedFirst = snapshot(NavigationPlan(home, listOf(stops[1])))
        assertSame(confirmed, confirmed.confirmPassedStop(plan, consumedFirst, 2_000))
        val atHome = allStopsRecorded()
        assertSame(atHome, atHome.confirmPassedStop(plan, snapshot(NavigationPlan(home)), 4_000))
        val finished = atHome.finish(plan, 4_000)
        assertSame(finished, finished.confirmPassedStop(plan, consumedFirst, 5_000))
    }

    @Test fun explicitPassedStopCompletionRetainsMonotonicRecordedTimes() {
        val pending = start()
        val consumedFirst = snapshot(NavigationPlan(home, listOf(stops[1])))
        assertThrows(IllegalArgumentException::class.java) {
            pending.confirmPassedStop(plan, consumedFirst, 999)
        }
        val firstDone = pending.confirmPassedStop(plan, consumedFirst, 2_000)
        val consumedSecond = snapshot(NavigationPlan(home, listOf(stops[2])))
        assertThrows(IllegalArgumentException::class.java) {
            firstDone.confirmPassedStop(plan, consumedSecond, 1_999)
        }
        val secondDone = firstDone.confirmPassedStop(plan, consumedSecond, 2_000)
        assertEquals(listOf(2_000L, 2_000L), secondDone.completedAtMillis)
        assertEquals(2, secondDone.completedStopCount)
    }

    @Test fun twoPassedStopsAreRecordedOnlyAfterExplicitConfirmationAndKeepTheThirdPending() {
        val initial = start()
        val observedHomeOnly = snapshot(NavigationPlan(home))
        assertSame(initial, initial.observeNavigation(observedHomeOnly))
        assertSame(initial, initial.confirmPassedStop(plan, observedHomeOnly, 2_000))
        val recovered = initial.confirmPassedStops(plan, observedHomeOnly, 2, 2_000)
        assertEquals(2, recovered.completedStopCount)
        assertEquals(listOf(2_000L, 2_000L), recovered.completedAtMillis)
        assertEquals(NavigationPlan(home), recovered.confirmedWindow)
        assertEquals(NavigationPlan(home, listOf(stops[2])), recovered.pendingWindow)
        assertTrue(recovered.navigationPending)
        assertSame(recovered, recovered.finish(plan, 3_000))
        assertSame(recovered, recovered.confirmPassedStops(plan, observedHomeOnly, 2, 2_001))
        assertEquals(stops, plan.stops.map { it.place.point })
    }

    @Test fun passedStopCountMustMatchExactlyTheConsumedPrefixInTheReadback() {
        val initial = start()
        val oneConsumed = snapshot(NavigationPlan(home, listOf(stops[1])))
        val twoConsumed = snapshot(NavigationPlan(home))
        assertSame(initial, initial.confirmPassedStops(plan, oneConsumed, 2, 2_000))
        assertSame(initial, initial.confirmPassedStops(plan, twoConsumed, 1, 2_000))
        assertEquals(initial.confirmPassedStop(plan, oneConsumed, 2_000),
            initial.confirmPassedStops(plan, oneConsumed, 1, 2_000))
    }

    @Test fun passedStopCountsOutsideTheTwoWaypointWindowAreRejectedWithoutChangingHistory() {
        val initial = start()
        val observedHomeOnly = snapshot(NavigationPlan(home))
        for (count in listOf(-1, 0, 3, Int.MAX_VALUE)) {
            assertSame(initial, initial.confirmPassedStops(plan, observedHomeOnly, count, 2_000))
        }
        val thirdPending = initial.confirmPassedStops(plan, observedHomeOnly, 2, 2_000)
        assertSame(thirdPending, thirdPending.confirmPassedStops(plan, observedHomeOnly, 2, 2_001))
        assertEquals(2, thirdPending.completedStopCount)
    }

    @Test fun twoPassedStopsRejectForeignEndedMissingAndUnconsumedVehicleRoutes() {
        val initial = start()
        val homeRoute = NavigationPlan(home)
        for (readback in listOf(
            snapshot(homeRoute.copy(destination = stops[2])),
            snapshot(homeRoute).copy(destination = null),
            snapshot(homeRoute).copy(guidanceActive = false),
            snapshot(homeRoute.copy(waypoints = listOf(stops[2]))),
            snapshot(initial.pendingWindow!!),
        )) {
            assertSame(initial, initial.confirmPassedStops(plan, readback, 2, 2_000))
        }
        assertTrue(initial.completedAtMillis.isEmpty())
    }

    @Test fun twoPassedStopsRequireTheSameValidSelectedPlanAndAPendingWindow() {
        val initial = start()
        val readback = snapshot(NavigationPlan(home))
        for (other in listOf(plan.copy(id = "other"), plan.copy(stops = plan.stops.reversed()),
            plan.copy(issues = listOf(SharedPlanIssue.ErrandNotReady)))) {
            assertSame(initial, initial.confirmPassedStops(other, readback, 2, 2_000))
        }
        val confirmed = confirm(initial)
        assertSame(confirmed, confirmed.confirmPassedStops(plan, readback, 2, 2_000))
        val atHome = allStopsRecorded()
        assertSame(atHome, atHome.confirmPassedStops(plan, readback, 1, 4_000))
        val finished = atHome.finish(plan, 4_000)
        assertSame(finished, finished.confirmPassedStops(plan, readback, 2, 5_000))
    }

    @Test fun twoPassedStopsKeepMonotonicTimesIncludingEqualTimesForOneApproval() {
        val initial = start()
        val homeReadback = snapshot(NavigationPlan(home))
        assertThrows(IllegalArgumentException::class.java) {
            initial.confirmPassedStops(plan, homeReadback, 2, 999)
        }
        val firstDone = initial.confirmPassedStop(plan, snapshot(NavigationPlan(home, listOf(stops[1]))), 2_000)
        assertThrows(IllegalArgumentException::class.java) {
            firstDone.confirmPassedStops(plan, homeReadback, 2, 1_999)
        }
        val allDone = firstDone.confirmPassedStops(plan, homeReadback, 2, 2_000)
        assertEquals(3, allDone.completedStopCount)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), allDone.completedAtMillis)
        assertEquals(NavigationPlan(home), allDone.confirmedWindow)
        assertEquals(NavigationPlan(home), allDone.pendingWindow)
    }

    @Test fun twoConsumedNativeWaypointsCanBeConfirmedAfterRestartWithoutInventingTheOldWindow() {
        val restarted = confirm(start()).afterRestart(plan, home)
        assertEquals(null, restarted.confirmedWindow)
        val recovered = restarted.confirmPassedStops(plan, snapshot(NavigationPlan(home)), 2, 2_000)
        assertEquals(2, recovered.completedStopCount)
        assertEquals(NavigationPlan(home), recovered.confirmedWindow)
        assertEquals(plan.currentWindow(home, 2), recovered.pendingWindow)
    }

    @Test fun afterTwoPassedStopsTheRemainingErrandStillNeedsItsOwnReadbackAndCompletion() {
        val recovered = start().confirmPassedStops(plan, snapshot(NavigationPlan(home)), 2, 2_000)
        assertSame(recovered, recovered.confirmStop(plan, 3_000))
        val errandGuiding = confirm(recovered)
        assertEquals(2, errandGuiding.completedStopCount)
        val errandDone = errandGuiding.confirmStop(plan, 3_000)
        assertEquals(3, errandDone.completedStopCount)
        assertEquals(listOf(2_000L, 2_000L, 3_000L), errandDone.completedAtMillis)
        assertEquals(NavigationPlan(home), errandDone.pendingWindow)
        assertEquals(JourneyStage.COMPLETE, errandDone.finish(plan, 4_000).stage)
    }

    @Test fun consumingBothStopsOfATwoStopPlanKeepsHomeForExplicitArrival() {
        val twoStopPlan = plan.copy(id = "a>b", stops = plan.stops.take(2))
        val initial = SharedJourneyProgress().compare(listOf(twoStopPlan)).review(listOf(twoStopPlan))
            .start(twoStopPlan, home, 1_000)
        val recovered = initial.confirmPassedStops(twoStopPlan, snapshot(NavigationPlan(home)), 2, 2_000)
        assertEquals(2, recovered.completedStopCount)
        assertEquals(NavigationPlan(home), recovered.confirmedWindow)
        assertEquals(NavigationPlan(home), recovered.pendingWindow)
        assertSame(recovered, recovered.confirmPassedStops(twoStopPlan, snapshot(NavigationPlan(home)), 1, 2_001))
        assertEquals(JourneyStage.COMPLETE, recovered.finish(twoStopPlan, 3_000).stage)
    }

    @Test fun addressDestinationWindowsKeepTheActualDriverHomeThroughEveryCompletionAndRestart() {
        val address = stops.last().copy(placeId = "", provider = "")
        val last = plan.stops.last() as SharedScheduledStop.Errand
        val addressPlan = plan.copy(stops = plan.stops.dropLast(1) +
            last.copy(errand = last.errand.copy(place = last.place.copy(point = address))))
        var progress = SharedJourneyProgress().compare(listOf(addressPlan)).review(listOf(addressPlan))
            .start(addressPlan, home, 1_000)
        assertEquals(NavigationPlan(address, stops.take(2)), progress.pendingWindow)
        assertEquals(home, progress.driverHome)
        for (completed in 0..2) {
            progress = confirm(progress)
            val restarted = progress.afterRestart(addressPlan, home)
            assertEquals(home, restarted.driverHome)
            assertEquals(progress.confirmedWindow, restarted.pendingWindow)
            assertEquals(null, restarted.confirmedWindow)
            assertSame(progress, progress.afterRestart(addressPlan, address))
            progress = progress.confirmStop(addressPlan, 2_000L + completed)
            assertEquals(completed + 1, progress.completedStopCount)
            assertEquals(home, progress.driverHome)
        }
        assertEquals(NavigationPlan(home), progress.pendingWindow)
        assertEquals(JourneyStage.COMPLETE, progress.finish(addressPlan, 3_000).stage)
    }

    private fun start(): SharedJourneyProgress = SharedJourneyProgress().compare(listOf(plan))
        .select(plan.id, listOf(plan)).review(listOf(plan)).start(plan, home, 1_000)

    private fun allStopsRecorded(): SharedJourneyProgress {
        var progress = confirm(start())
        for (index in stops.indices) {
            progress = progress.confirmStop(plan, 2_000L + index)
            if (index < stops.lastIndex) progress = confirm(progress)
        }
        return progress
    }

    private fun confirm(progress: SharedJourneyProgress): SharedJourneyProgress =
        progress.observeNavigation(snapshot(progress.pendingWindow!!))

    private fun snapshot(window: NavigationPlan) = NavigationSnapshot(
        observed(window.destination), window.waypoints.map(::observed), true,
    )

    private fun observed(point: NavigationPoint) = ObservedNavigationPlace(
        point.name, placeId = point.placeId, latitude = point.latitude, longitude = point.longitude,
    )

    private fun point(id: String, latitude: Double) = NavigationPoint(id, latitude, 127.0, placeId = id)
}
