package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.ObservedNavigationPlace
import io.moduhome.app.core.navigation.remainingAfter
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
import io.moduhome.app.core.shared.SharedScheduledStop
import io.moduhome.app.core.shared.currentWindow
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.JourneyProposalInbox
import io.moduhome.app.core.transfer.ProposalInboxEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SharedJourneySessionTest {
    private val now = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val origin = NavigationPoint("출발", 37.50, 127.0)
    private val home = NavigationPoint("운전자 집", 37.51, 127.01)
    private val checked = DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "사용자 확인", now - 500)
    private val draft = SharedJourneyDraft(home, listOf(
        SharedPassengerDraft("p1", "동승자 1", NavigationPoint("집 1", 37.52, 127.02, placeId = "poi-1"), true, now - 1000, checked),
        SharedPassengerDraft("p2", "동승자 2", NavigationPoint("집 2", 37.53, 127.03, placeId = "poi-2"), true, now - 1000, checked),
    ), SharedErrandDraft(VisitKind.PICKUP, NavigationPoint("픽업", 37.54, 127.04, placeId = "poi-pickup"), ready = true))
    private val routing by lazy {
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>) = RouteEstimateResult.Success(response(points))
        }
        (runBlocking { loadSharedRoutedJourney(draft, origin, gateway, { now }, zone) }
            as SharedRoutedJourneyLoadResult.Success).estimates
    }
    private val plan get() = routing.plans.first()
    private val compared get() = SharedJourneyProgress().compare(routing.plans)
    private val reviewed get() = compared.review(routing.plans)

    private fun budgetLimitedSession(): SharedJourneySession {
        val input = draft.copy(allowedAdditionalMinutes = 0)
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>) = RouteEstimateResult.Success(response(points))
        }
        val estimates = (runBlocking { loadSharedRoutedJourney(input, origin, gateway, { now }, zone) }
            as SharedRoutedJourneyLoadResult.Success).estimates
        return SharedJourneySession(input, SharedJourneyProgress().compare(estimates.plans), estimates)
    }

    @Test fun budgetChoiceKeepsPassengersAndErrandButRequiresANewRoadComparison() {
        val old = budgetLimitedSession()
        val suggestion = requireNotNull(old.additionalTimeSuggestion)
        val next = requireNotNull(old.withAdditionalTime(suggestion))
        assertEquals(old.draft.copy(allowedAdditionalMinutes = suggestion.requiredMinutes), next.draft)
        assertEquals(SharedJourneyProgress(), next.progress)
        assertNull(next.routing)
        assertNull(next.additionalTimeSuggestion)
        assertEquals(old.acceptedProposals, next.acceptedProposals)
    }

    @Test fun changedSharedDraftAndNonComparisonStagesRejectOldBudgetChoice() {
        val old = budgetLimitedSession()
        val suggestion = requireNotNull(old.additionalTimeSuggestion)
        val changed = old.copy(draft = old.draft.copy(allowedAdditionalMinutes = 20))
        assertNull(changed.withAdditionalTime(suggestion))
        for (stage in JourneyStage.entries.filter { it != JourneyStage.COMPARE }) {
            val state = old.copy(progress = old.progress.copy(stage = stage))
            assertNull(state.additionalTimeSuggestion)
            assertNull(state.withAdditionalTime(suggestion))
        }
    }
    private val starting get() = reviewed.start(plan, home, now + 1)
    private val active get() = starting.observeNavigation(snapshot(plan.currentWindow(home, 0)))

    private fun response(points: List<NavigationPoint>, version: String = "test-map", queriedAt: Long = now): RouteEstimate =
        (RouteEstimate.fromObservation(points, List(points.size - 1) { ObservedRouteLeg(60.0, 600.0) },
            points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
            (points.size - 1) * 60.0, (points.size - 1) * 600.0, queriedAt, version) as RouteEstimateResult.Success).estimate

    private fun observed(point: NavigationPoint) = ObservedNavigationPlace(point.name, point.placeId, point.provider,
        point.latitude, point.longitude, point.subId)
    private fun snapshot(window: NavigationPlan) = NavigationSnapshot(observed(window.destination), window.waypoints.map(::observed), true)

    private fun restoreRouting(
        input: SharedJourneyDraft = draft,
        direct: RouteEstimate = routing.direct,
        windows: Map<String, List<SharedGuidanceWindowEstimate>> = routing.windows,
        validatedAt: Long = routing.validatedAtMillis,
    ) = SharedRoutedJourneyEstimates.restore(input, routing.origin, routing.departureAtMillis, direct,
        windows, validatedAt, routing.zoneId)

    @Test fun aValidatedHistoricalSetRebuildsEveryOrderWithoutRefreshingItsAge() {
        val restored = requireNotNull(restoreRouting())
        assertEquals(routing.plans, restored.plans)
        assertEquals(routing.queriedAtEpochMillis, restored.queriedAtEpochMillis)
        assertEquals(routing.validatedAtMillis, restored.validatedAtMillis)
        assertEquals(zone, restored.zoneId)
        assertFalse(restored.isFresh(now + 2 * 24 * 60 * 60 * 1000L))
    }

    @Test fun aMissingOrderCannotBeRestoredFromOnlyTheSavedSelectedOrder() {
        assertNull(restoreRouting(windows = routing.windows - routing.windows.keys.first()))
    }

    @Test fun alteredConsumedIndicesCannotIncludeTheUnusedInitialTail() {
        val id = routing.windows.keys.first()
        val windows = routing.windows.toMutableMap()
        windows[id] = windows.getValue(id).mapIndexed { index, window ->
            if (index == 0) window.copy(consumedLegIndices = listOf(0, 1, 2)) else window
        }
        assertNull(restoreRouting(windows = windows))
    }

    @Test fun invalidConsumedIndicesAreRejectedBeforeIndexingTheRoute() {
        val id = routing.windows.keys.first()
        val windows = routing.windows.toMutableMap()
        windows[id] = windows.getValue(id).mapIndexed { index, window ->
            if (index == 0) window.copy(consumedLegIndices = listOf(99)) else window
        }
        assertNull(restoreRouting(windows = windows))
    }

    @Test fun aDifferentWindowEndpointCannotBeRestored() {
        val id = routing.windows.keys.first()
        val windows = routing.windows.toMutableMap()
        windows[id] = windows.getValue(id).mapIndexed { index, window ->
            if (index == 0) window.copy(route = response(window.route.points.dropLast(1) + home.copy(placeId = "other"))) else window
        }
        assertNull(restoreRouting(windows = windows))
    }

    @Test fun changedHomeOrMixedMapVersionsInvalidateRestoration() {
        assertNull(restoreRouting(input = draft.copy(driverHome = home.copy(placeId = "different-home"))))
        assertNull(restoreRouting(direct = response(routing.direct.points, version = "other-map")))
    }

    @Test fun theRecordedValidationTimeMustActuallyHaveSeenAllResponsesAsFresh() {
        assertNull(restoreRouting(validatedAt = now - 1))
        assertNull(restoreRouting(validatedAt = now + RouteEstimate.FRESHNESS_MILLIS + 1))
    }

    @Test fun aFreshComparisonAndReviewPreserveTheirSelectedPlan() {
        listOf(compared, reviewed).forEach { progress ->
            val restored = SharedJourneySession.restore(draft, progress, routing, now + 10)
            assertEquals(progress, restored.progress)
            assertEquals(routing, restored.routing)
        }
    }

    @Test fun anExpiredComparisonOrReviewReturnsToInputForANewQuery() {
        listOf(compared, reviewed).forEach { progress ->
            val restored = SharedJourneySession.restore(draft, progress, routing, now + RouteEstimate.FRESHNESS_MILLIS + 1)
            assertEquals(JourneyStage.INPUT, restored.progress.stage)
            assertEquals(draft, restored.draft)
            assertNull(restored.routing)
        }
    }

    @Test fun anActiveOldRecordKeepsTheFullPlanButRequestsActualVehicleReadback() {
        val restored = SharedJourneySession.restore(draft, active, routing, now + 2 * 24 * 60 * 60 * 1000L)
        assertEquals(JourneyStage.ACTIVE, restored.progress.stage)
        assertEquals(active.selectedPlanId, restored.progress.selectedPlanId)
        assertEquals(routing, restored.routing)
        assertNull(restored.progress.confirmedWindow)
        assertEquals(plan.currentWindow(home, 0), restored.progress.pendingWindow)
        assertEquals(3, restored.routing?.plans?.first()?.stops?.size)
    }

    @Test fun aPhysicalCompletionAndItsFailedNextWindowSurviveRestartSeparately() {
        val pending = active.confirmStop(plan, now + 10)
        val restored = SharedJourneySession.restore(draft, pending, routing, now + 1000)
        assertEquals(1, restored.progress.completedStopCount)
        assertEquals(listOf(now + 10), restored.progress.completedAtMillis)
        assertEquals(plan.currentWindow(home, 1), restored.progress.pendingWindow)
        assertNull(restored.progress.confirmedWindow)
    }

    @Test fun aUserConfirmedPassedStopKeepsItsPhysicalRecordAndNextWindowAcrossRestart() {
        val previous = plan.currentWindow(home, 0)
        val actualSuffix = previous.remainingAfter(1)
        val progressed = starting.confirmPassedStop(plan, snapshot(actualSuffix), now + 10)
        assertEquals(1, progressed.completedStopCount)
        assertEquals(listOf(now + 10), progressed.completedAtMillis)
        assertEquals(actualSuffix, progressed.confirmedWindow)
        assertEquals(plan.currentWindow(home, 1), progressed.pendingWindow)
        val restored = SharedJourneySession.restore(draft, progressed, routing, now + 1000)
        assertEquals(JourneyStage.ACTIVE, restored.progress.stage)
        assertEquals(1, restored.progress.completedStopCount)
        assertEquals(listOf(now + 10), restored.progress.completedAtMillis)
        assertEquals(plan.currentWindow(home, 1), restored.progress.pendingWindow)
        // Historical B does not prove that the vehicle now has the required B,C window.
        assertNull(restored.progress.confirmedWindow)
    }

    @Test fun aForeignSuffixIsNotAcceptedAsTheHistoricalConfirmation() {
        val actualSuffix = plan.currentWindow(home, 0).remainingAfter(1)
        val progressed = starting.confirmPassedStop(plan, snapshot(actualSuffix), now + 10)
        val foreign = NavigationPlan(home, listOf(home.copy(name = "다른 정차", placeId = "foreign")))
        val restored = SharedJourneySession.restore(draft, progressed.copy(confirmedWindow = foreign), routing, now + 1000)
        assertEquals(JourneyStage.INPUT, restored.progress.stage)
        assertNull(restored.routing)
    }

    @Test fun aSuffixWithoutARecordedPhysicalCompletionCannotBeRestoredAsConfirmation() {
        val suffix = plan.currentWindow(home, 0).remainingAfter(1)
        val wrong = starting.copy(confirmedWindow = suffix)
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, wrong, routing, now + 1000).progress.stage)
    }

    @Test fun anOldObservedSuffixCannotStandInForTheMissingNextPendingWindow() {
        val actualSuffix = plan.currentWindow(home, 0).remainingAfter(1)
        val progressed = starting.confirmPassedStop(plan, snapshot(actualSuffix), now + 10)
        assertEquals(JourneyStage.INPUT,
            SharedJourneySession.restore(draft, progressed.copy(pendingWindow = null), routing, now + 1000).progress.stage)
    }

    @Test fun twoExplicitlyConfirmedPassedStopsSurviveRestartWithOnlyTheErrandPending() {
        assertTrue(plan.stops.last() is SharedScheduledStop.Errand)
        val progressed = starting.confirmPassedStops(plan, snapshot(NavigationPlan(home)), 2, now + 10)
        assertEquals(NavigationPlan(home), progressed.confirmedWindow)
        assertEquals(plan.currentWindow(home, 2), progressed.pendingWindow)
        val restored = SharedJourneySession.restore(draft, progressed, routing, now + 2 * 24 * 60 * 60 * 1000L)
        assertEquals(JourneyStage.ACTIVE, restored.progress.stage)
        assertEquals(2, restored.progress.completedStopCount)
        assertEquals(listOf(now + 10, now + 10), restored.progress.completedAtMillis)
        assertEquals(plan.currentWindow(home, 2), restored.progress.pendingWindow)
        assertNull(restored.progress.confirmedWindow)
        assertEquals(routing, restored.routing)
    }

    @Test fun aRestoredTwoStopCompletionCanConnectAndCompleteTheRemainingErrand() {
        val passed = starting.confirmPassedStops(plan, snapshot(NavigationPlan(home)), 2, now + 10)
        val restored = SharedJourneySession.restore(draft, passed, routing, now + 1000)
        val errandGuiding = restored.progress.observeNavigation(snapshot(requireNotNull(restored.progress.pendingWindow)))
        assertEquals(2, errandGuiding.completedStopCount)
        val allDone = errandGuiding.confirmStop(plan, now + 1100)
        assertEquals(plan.stops.size, allDone.completedStopCount)
        val finished = allDone.finish(plan, now + 1200)
        val completed = SharedJourneySession.restore(draft, finished, routing, now + 1300)
        assertEquals(JourneyStage.COMPLETE, completed.progress.stage)
        assertEquals(listOf(now + 10, now + 10, now + 1100), completed.progress.completedAtMillis)
        assertEquals(NavigationPlan(home), completed.progress.pendingWindow)
        assertFalse(completed.progress.navigationPending)
    }

    @Test fun aHomeOnlyObservationCannotJustifyFewerThanTwoCompletedStopsOfTheInitialWindow() {
        val homeOnly = NavigationPlan(home)
        val oneCompleted = active.confirmStop(plan, now + 10)
        for (wrong in listOf(starting.copy(confirmedWindow = homeOnly), oneCompleted.copy(confirmedWindow = homeOnly))) {
            assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, wrong, routing, now + 1000).progress.stage)
        }
    }

    @Test fun twoCompletedStopsCannotRetainAnUnconsumedOrWronglyConsumedInitialWindow() {
        val passed = starting.confirmPassedStops(plan, snapshot(NavigationPlan(home)), 2, now + 10)
        val initialWindow = plan.currentWindow(home, 0)
        for (wrongWindow in listOf(initialWindow, initialWindow.remainingAfter(1))) {
            val wrong = passed.copy(confirmedWindow = wrongWindow)
            assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, wrong, routing, now + 1000).progress.stage)
        }
    }

    @Test fun aTwoStopCompletionCannotRestoreAForeignHomeAsItsObservedSuffix() {
        val passed = starting.confirmPassedStops(plan, snapshot(NavigationPlan(home)), 2, now + 10)
        val foreign = passed.copy(confirmedWindow = NavigationPlan(home.copy(placeId = "foreign-home")))
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, foreign, routing, now + 1000).progress.stage)
    }

    @Test fun twoPassedStopsStillNeedTheRemainingErrandAsThePendingWindow() {
        val passed = starting.confirmPassedStops(plan, snapshot(NavigationPlan(home)), 2, now + 10)
        for (pending in listOf(null, NavigationPlan(home))) {
            val wrong = passed.copy(pendingWindow = pending)
            assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, wrong, routing, now + 1000).progress.stage)
        }
    }

    @Test fun completedHistorySurvivesExpiredRouteTimesWithoutCreatingPendingNavigation() {
        var progress = active
        repeat(plan.stops.size) { index ->
            progress = progress.confirmStop(plan, now + 10 + index)
            progress = progress.observeNavigation(snapshot(requireNotNull(progress.pendingWindow)))
        }
        progress = progress.finish(plan, now + 20)
        val restored = SharedJourneySession.restore(draft, progress, routing, now + 2 * 24 * 60 * 60 * 1000L)
        assertEquals(JourneyStage.COMPLETE, restored.progress.stage)
        assertEquals(plan.stops.size, restored.progress.completedStopCount)
        assertEquals(progress.completedAtMillis, restored.progress.completedAtMillis)
        assertNull(restored.progress.pendingWindow)
        assertEquals(NavigationPlan(home), restored.progress.confirmedWindow)
    }

    @Test fun anUnconfirmedStartRemainsPendingAndNeverBecomesConfirmedFromStorageAlone() {
        val restored = SharedJourneySession.restore(draft, starting, routing, now + 10)
        assertEquals(JourneyStage.ACTIVE, restored.progress.stage)
        assertNull(restored.progress.confirmedWindow)
        assertEquals(starting.pendingWindow, restored.progress.pendingWindow)
    }

    @Test fun aMissingSelectedPlanOrMissingRoutingFallsBackToInput() {
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, active.copy(selectedPlanId = "missing"), routing, now + 10).progress.stage)
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, active, null, now + 10).progress.stage)
    }

    @Test fun aWindowToADifferentHomeIsRejected() {
        val wrong = active.copy(confirmedWindow = active.confirmedWindow?.copy(destination = home.copy(placeId = "other")))
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, wrong, routing, now + 10).progress.stage)
    }

    @Test fun completeCannotBeRestoredWhilePhysicalStopsRemain() {
        assertEquals(JourneyStage.INPUT,
            SharedJourneySession.restore(draft, active.copy(stage = JourneyStage.COMPLETE), routing, now + 10).progress.stage)
    }

    @Test fun explicitHomeArrivalPreservesUnconfirmedHomeIntentWithoutInventingASdkConfirmation() {
        var progress = active
        repeat(plan.stops.size) { index ->
            progress = progress.confirmStop(plan, now + 10 + index)
            if (index < plan.stops.lastIndex) progress = progress.observeNavigation(snapshot(requireNotNull(progress.pendingWindow)))
        }
        val completed = progress.finish(plan, now + 20)
        val restored = SharedJourneySession.restore(draft, completed, routing, now + 2 * 24 * 60 * 60 * 1000L)
        assertEquals(JourneyStage.COMPLETE, restored.progress.stage)
        assertEquals(NavigationPlan(home), restored.progress.pendingWindow)
        assertEquals(progress.confirmedWindow, restored.progress.confirmedWindow)
        assertNotEquals(NavigationPlan(home), restored.progress.confirmedWindow)
        assertFalse(restored.progress.navigationPending)
    }

    @Test fun homeArrivalAfterRestartCanBeRestoredWithoutAnyCurrentSdkConfirmation() {
        var progress = active
        repeat(plan.stops.size) { index ->
            progress = progress.confirmStop(plan, now + 10 + index)
            if (index < plan.stops.lastIndex) progress = progress.observeNavigation(snapshot(requireNotNull(progress.pendingWindow)))
        }
        val restarted = progress.afterRestart(plan, home)
        val completed = restarted.finish(plan, now + 20)
        val restored = SharedJourneySession.restore(draft, completed, routing, now + 1000)
        assertEquals(JourneyStage.COMPLETE, restored.progress.stage)
        assertNull(restored.progress.confirmedWindow)
        assertEquals(NavigationPlan(home), restored.progress.pendingWindow)
        assertFalse(restored.progress.navigationPending)
    }

    @Test fun aPendingWindowCannotSkipTheFirstUncompletedStop() {
        val wrong = active.copy(pendingWindow = plan.currentWindow(home, 1))
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, wrong, routing, now + 10).progress.stage)
    }

    @Test fun aFutureStartOrCompletionIsNotAcceptedAsHistory() {
        val futureStart = active.copy(startedAtMillis = now + 100)
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, futureStart, routing, now + 10).progress.stage)
        val futureStop = active.confirmStop(plan, now + 100)
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(draft, futureStop, routing, now + 10).progress.stage)
    }

    @Test fun impossibleCompletionCountsForTheSelectedPlanAreRejected() {
        val singleDraft = draft.copy(passengers = draft.passengers.take(1), errand = null)
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>) = RouteEstimateResult.Success(response(points))
        }
        val single = (runBlocking { loadSharedRoutedJourney(singleDraft, origin, gateway, { now }, zone) }
            as SharedRoutedJourneyLoadResult.Success).estimates
        val progress = SharedJourneyProgress(JourneyStage.ACTIVE, single.plans.single().id, 2,
            listOf(now + 10, now + 11), now + 1, NavigationPlan(home), NavigationPlan(home))
        assertEquals(JourneyStage.INPUT, SharedJourneySession.restore(singleDraft, progress, single, now + 20).progress.stage)
    }

    @Test fun changedInputCannotReuseAnOldActiveRouteRecord() {
        val changed = draft.copy(passengers = draft.passengers.mapIndexed { index, person ->
            if (index == 0) person.chooseDropoff(requireNotNull(person.dropoff).copy(placeId = "new-door")) else person
        })
        val restored = SharedJourneySession.restore(changed, active, routing, now + 10)
        assertEquals(JourneyStage.INPUT, restored.progress.stage)
        assertEquals(changed, restored.draft)
        assertNull(restored.routing)
    }

    @Test fun staleRoutingAndInputResetKeepAcceptedProposalsAcrossChangedDrafts() {
        val proposal = ErrandProposal("proposal-001", 1, now - 1000, now + 3_600_000,
            VisitKind.GROCERIES, NavigationPoint("받은 장보기", 37.55, 127.05), 12, 2)
        val history = listOf(SharedProposalAcceptance(proposal, now - 500))
        val restored = SharedJourneySession.restore(draft, compared, routing,
            now + RouteEstimate.FRESHNESS_MILLIS + 1, history)
        assertEquals(JourneyStage.INPUT, restored.progress.stage)
        assertEquals(history, restored.acceptedProposals)
        val changed = draft.copy(allowedAdditionalMinutes = 60, errand = null)
        val reset = SharedJourneySession(draft, active, routing, history).resetToInput(changed)
        assertEquals(changed, reset.draft)
        assertEquals(SharedJourneyProgress(), reset.progress)
        assertNull(reset.routing)
        assertEquals(history, reset.acceptedProposals)
    }

    @Test fun acceptingAProposalInvalidatesCachedRoutingAndRetainsPassengerConfirmations() {
        val proposal = ErrandProposal("proposal-001", 1, now, now + 3_600_000,
            VisitKind.GROCERIES, NavigationPoint("받은 장보기", 37.55, 127.05), 12, 2, false, now + 1_800_123)
        val inbox = JourneyProposalInbox.restore(listOf(ProposalInboxEntry(proposal)))
        val input = SharedJourneySession(draft, routing = routing)
        val review = (input.reviewProposal(inbox, proposal.id, proposal.revision, now, zone) as SharedProposalReviewResult.Ready).review
        val applied = input.approveProposal(review, inbox, now + 1, zone) as SharedProposalApplyResult.Applied
        assertNull(applied.session.routing)
        assertEquals(draft.passengers, applied.session.draft.passengers)
        assertEquals(draft.driverHome, applied.session.draft.driverHome)
        assertEquals(SharedJourneyProgress(), applied.session.progress)
    }

    @Test fun clockRollbackPreservesHistoryWhileDuplicateReceiptIdsAreRejected() {
        val proposal = ErrandProposal("proposal-001", 1, now, now + 3_600_000,
            VisitKind.GROCERIES, NavigationPoint("받은 장보기", 37.55, 127.05), 12)
        val future = SharedProposalAcceptance(proposal, now + 100)
        val receipt = SharedProposalAcceptance(proposal, now)
        val rollback = SharedJourneySession.restore(draft, compared, routing, now + 10, listOf(future))
        assertEquals(JourneyStage.INPUT, rollback.progress.stage)
        assertNull(rollback.routing)
        assertEquals(listOf(future), rollback.acceptedProposals)
        val duplicate = SharedJourneySession.restore(draft, compared, routing, now + 10, listOf(receipt, receipt))
        assertEquals(JourneyStage.INPUT, duplicate.progress.stage)
        assertNull(duplicate.routing)
        assertTrue(duplicate.acceptedProposals.isEmpty())
    }
}
