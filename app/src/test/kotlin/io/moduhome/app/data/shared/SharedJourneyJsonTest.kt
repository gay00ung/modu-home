package io.moduhome.app.data.shared

import io.moduhome.app.core.navigation.NavigationPoint
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
import io.moduhome.app.core.shared.currentWindow
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.ErrandProposalInbox
import io.moduhome.app.core.transfer.ProposalInboxEntry
import io.moduhome.app.core.transfer.ProposalReviewStatus
import io.moduhome.app.feature.shared.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SharedJourneyJsonTest {
    private val now = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val home = NavigationPoint("운전자 집", 37.50, 127.0)
    private val proposal = ErrandProposal("proposal-001", 2, now, now + 3_600_000, VisitKind.PICKUP,
        NavigationPoint("픽업 & 커피 ☕", 37.52, 127.02, "매장 / 입구 #2", "poi/1", "sub-1", "provider"),
        12, 2, false, now + 1_800_123)
    private val receipt = SharedProposalAcceptance(proposal, now)
    private val draft = SharedJourneyDraft(home, listOf(SharedPassengerDraft("p1", "동승자 1",
        NavigationPoint("집 앞", 37.51, 127.01), true, now - 1000,
        DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "직접 확인", now - 1000))),
        SharedErrandDraft(proposal.kind, proposal.place, 12, 2, false, proposal.deadlineAtMillis), 60)
    private val session get() = SharedJourneySession(draft, acceptedProposals = listOf(receipt))
    private fun encoded(current: SharedJourneySession = session) = requireNotNull(SharedJourneyJson.encode(current))

    private fun routedSession(): SharedJourneySession {
        val input = draft.copy(errand = requireNotNull(draft.errand).copy(ready = true))
        val gateway = object : RouteEstimateGateway {
            override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult = RouteEstimate.fromObservation(
                points, List(points.size - 1) { ObservedRouteLeg(60.0, 600.0) },
                points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
                (points.size - 1) * 60.0, (points.size - 1) * 600.0, now, "test-map",
            )
        }
        val routing = (runBlocking { loadSharedRoutedJourney(input, NavigationPoint("현재 위치", 37.53, 127.03), gateway, { now }, zone) }
            as SharedRoutedJourneyLoadResult.Success).estimates
        return SharedJourneySession(input, SharedJourneyProgress().compare(routing.plans), routing, listOf(receipt))
    }

    @Test fun roundTripPreservesEveryPlaceAndProposalConditionWithoutClaimingReadiness() {
        val restored = SharedJourneyJson.decode(encoded(), now + 1)
        assertEquals(session, restored)
        assertEquals(proposal, restored.acceptedProposals.single().proposal)
        assertEquals(proposal.place, restored.draft.errand!!.place)
        assertEquals(proposal.deadlineAtMillis, restored.draft.errand!!.deadlineAtMillis)
        assertEquals(12, restored.draft.errand!!.stayMinutes)
        assertEquals(2, restored.draft.errand!!.walkingMinutesOneWay)
        assertFalse(restored.draft.errand!!.ready)
    }

    @Test fun originalSchemaOneWithoutAcceptanceHistoryLoadsAsTheOriginalInput() {
        val original = """{
            "schema":1,
            "draft":{"budget":30,"passengers":[{
                "id":"passenger-1","name":"동승자 1","doorstep":false,"stop":1,
                "verification":{"vehicle":"NOT_CHECKED","pedestrian":"NOT_CHECKED","source":""}
            }]},
            "progress":{"stage":"INPUT","completed":0,"completedAt":[]}
        }"""
        assertEquals(SharedJourneySession(), SharedJourneyJson.decode(original, now))
    }

    @Test fun expiredAcceptedProposalsRemainHistoryInsteadOfBeingDecodedAsNewSuggestions() {
        val old = proposal.copy(createdAtMillis = now - 10_800_000, expiresAtMillis = now - 3_600_000, deadlineAtMillis = null)
        val historical = session.copy(acceptedProposals = listOf(SharedProposalAcceptance(old, now - 7_200_000)))
        assertEquals(historical, SharedJourneyJson.decode(encoded(historical), now + 1))
    }

    @Test fun staleComparisonReturnsToInputAndKeepsDurableAcceptanceHistory() {
        val compared = routedSession()
        val restored = SharedJourneyJson.decode(encoded(compared), now + RouteEstimate.FRESHNESS_MILLIS + 1)
        assertEquals(JourneyStage.INPUT, restored.progress.stage)
        assertNull(restored.routing)
        assertEquals(compared.draft, restored.draft)
        assertEquals(listOf(receipt), restored.acceptedProposals)
    }

    @Test fun corruptRoutingOrProgressCannotEraseIndependentlyValidAcceptanceHistory() {
        val compared = routedSession()
        val malformedRoute = JSONObject(encoded(compared)).put("routing", JSONObject().put("schema", 1).put("orders", JSONArray()))
        val malformedProgress = JSONObject(encoded(compared)).put("progress", JSONObject().put("stage", "UNKNOWN"))
        for (raw in listOf(malformedRoute, malformedProgress)) {
            val restored = SharedJourneyJson.decode(raw.toString(), now + 10)
            assertEquals(JourneyStage.INPUT, restored.progress.stage)
            assertNull(restored.routing)
            assertEquals(listOf(receipt), restored.acceptedProposals)
        }
    }

    @Test fun activeRestoreKeepsHistoryButWaitsForActualVehicleReadback() {
        val compared = routedSession()
        val routes = requireNotNull(compared.routing)
        val plan = routes.plans.first()
        val progress = compared.progress.review(routes.plans).start(plan, home, now + 1)
        val started = compared.copy(progress = progress)
        val restored = SharedJourneyJson.decode(encoded(started), now + 100)
        assertEquals(JourneyStage.ACTIVE, restored.progress.stage)
        assertEquals(listOf(receipt), restored.acceptedProposals)
        assertEquals(plan.currentWindow(home, 0), restored.progress.pendingWindow)
        assertNull(restored.progress.confirmedWindow)
    }

    @Test fun clockRollbackPreservesAcceptedHistoryAndBlocksAnotherApprovalUntilTimeRecovers() {
        val restored = SharedJourneyJson.decode(encoded(), now - 60_000)
        assertEquals(JourneyStage.INPUT, restored.progress.stage)
        assertEquals(listOf(receipt), restored.acceptedProposals)
        val other = proposal.copy(id = "proposal-002")
        val pendingOther = ErrandProposalInbox.restore(listOf(ProposalInboxEntry(other)))
        val blocked = restored.reviewProposal(pendingOther, other.id, other.revision, now - 60_000, zone)
        assertTrue(blocked is SharedProposalReviewResult.Rejected)
        val pendingOriginal = ErrandProposalInbox.restore(listOf(ProposalInboxEntry(proposal)))
        assertEquals(ProposalReviewStatus.APPROVED, restored.reconcileProposalInbox(pendingOriginal).entries.single().status)
        assertTrue(restored.reviewProposal(pendingOriginal, proposal.id, proposal.revision, now + 1, zone) is SharedProposalReviewResult.Rejected)
    }

    @Test fun acceptanceAfterItsOwnExpiryCannotBeTrustedAsHistory() {
        val afterExpiry = JSONObject(encoded())
        afterExpiry.getJSONArray("acceptedProposals").getJSONObject(0).put("appliedAt", proposal.expiresAtMillis)
        val restored = SharedJourneyJson.decode(afterExpiry.toString(), now + 7_200_000)
        assertEquals(JourneyStage.INPUT, restored.progress.stage)
        assertTrue(restored.acceptedProposals.isEmpty())
    }

    @Test fun duplicateIdsAndOversizedHistoryAreRejectedOnWritingAndReading() {
        val duplicate = session.copy(acceptedProposals = listOf(receipt, receipt.copy(proposal = proposal.copy(revision = 3))))
        assertNull(SharedJourneyJson.encode(duplicate))
        val history = List(ErrandProposalInbox.MAX_ENTRIES) { index ->
            receipt.copy(proposal = proposal.copy(id = "proposal-${index.toString().padStart(3, '0')}"))
        }
        val full = session.copy(acceptedProposals = history)
        assertEquals(history, SharedJourneyJson.decode(encoded(full), now).acceptedProposals)
        assertNull(SharedJourneyJson.encode(full.copy(acceptedProposals = history + receipt)))
        val raw = JSONObject(encoded(full))
        raw.getJSONArray("acceptedProposals").put(JSONObject(encoded()).getJSONArray("acceptedProposals").getJSONObject(0))
        assertTrue(SharedJourneyJson.decode(raw.toString(), now).acceptedProposals.isEmpty())
    }

    @Test fun numericStringsAndWrongBooleanTypesDoNotSilentlyChangeStoredProposalConditions() {
        for ((key, value) in listOf("revision" to "2", "stay" to "12", "ready" to "false")) {
            val raw = JSONObject(encoded())
            raw.getJSONArray("acceptedProposals").getJSONObject(0).getJSONObject("proposal").put(key, value)
            assertTrue(SharedJourneyJson.decode(raw.toString(), now).acceptedProposals.isEmpty())
        }
    }
}
