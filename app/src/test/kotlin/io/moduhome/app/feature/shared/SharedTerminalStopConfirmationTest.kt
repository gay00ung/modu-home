package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.ObservedNavigationPlace
import io.moduhome.app.core.routing.*
import io.moduhome.app.core.shared.*
import io.moduhome.app.core.vehicle.DrivingInteraction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SharedTerminalStopConfirmationTest {
    private val now = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()
    private val allowed = DrivingInteraction.Known(false)
    private val ended = NavigationSnapshot(null, emptyList(), false)
    private val session by lazy {
        val home = NavigationPoint("운전자 집", 37.50, 127.0, placeId = "home")
        val address = NavigationPoint("검색한 하차 주소", 37.51, 127.01)
        val draft = SharedJourneyDraft(home, listOf(SharedPassengerDraft("p1", "동승자 1", address, true, now - 500,
            DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "직접 확인", now - 500))))
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>) = RouteEstimate.fromObservation(
                points, List(points.size - 1) { ObservedRouteLeg(60.0, 600.0) },
                points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
                (points.size - 1) * 60.0, (points.size - 1) * 600.0, now, "test-map",
            )
        }
        val routing = (runBlocking { loadSharedRoutedJourney(draft, NavigationPoint("현재 위치", 37.49, 127.0), gateway,
            { now }, ZoneId.of("Asia/Seoul")) } as SharedRoutedJourneyLoadResult.Success).estimates
        val plan = routing.plans.single()
        SharedJourneySession(draft, SharedJourneyProgress().compare(routing.plans).review(routing.plans)
            .start(plan, home, now + 1), routing)
    }
    private val confirmation get() = SharedTerminalStopConfirmation(session, requireNotNull(session.routing).plans.single(), ended, 7, allowed)

    @Test fun unchangedInactiveReadbackAuthorizesOnlyTheObservedNoGuidanceState() {
        val approval = confirmation.approvalAfterRead(session, allowed, ended, 7, 7)
        assertNotNull(approval)
        assertEquals(ended, approval!!.snapshot)
        assertEquals(7L, approval.routeRevision)
        assertTrue(session.progress.navigationPending)
        assertEquals(0, session.progress.completedStopCount)
        assertNull(session.progress.confirmedWindow)
    }

    @Test fun changesBeforeOrDuringTheReadInvalidateTheOldApproval() {
        for ((before, after) in listOf(8L to 8L, 7L to 8L, -1L to -1L)) {
            assertNull(confirmation.approvalAfterRead(session, allowed, ended, before, after))
        }
    }

    @Test fun missingDifferentOrActiveReadbackNeverRecordsTheOldCompletion() {
        val other = ObservedNavigationPlace("다른 목적지", latitude = 37.55, longitude = 127.05)
        for (snapshot in listOf(null, ended.copy(guidanceActive = true), ended.copy(destination = other),
            ended.copy(waypoints = listOf(other)))) {
            assertNull(confirmation.approvalAfterRead(session, allowed, snapshot, 7, 7))
            assertEquals(0, session.progress.completedStopCount)
        }
    }

    @Test fun changedDraftProgressOrSelectedFullPlanInvalidatesTheCompletion() {
        for (changed in listOf(session.resetToInput(), session.copy(draft = session.draft.copy(allowedAdditionalMinutes = 61)),
            session.copy(progress = session.progress.copy(selectedPlanId = "other")))) {
            assertNull(confirmation.approvalAfterRead(changed, allowed, ended, 7, 7))
        }
    }

    @Test fun unknownRestrictedAndChangedEditableDisplayResponsesRequireNewConfirmation() {
        for (interaction in listOf(DrivingInteraction.Unknown(), DrivingInteraction.Known(true),
            DrivingInteraction.Known(false, maximumTextLength = 20), DrivingInteraction.NotAutomotive)) {
            assertNull(confirmation.approvalAfterRead(session, interaction, ended, 7, 7))
        }
    }
}
