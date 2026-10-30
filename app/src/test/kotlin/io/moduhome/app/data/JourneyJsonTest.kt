package io.moduhome.app.data

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.*
import io.moduhome.app.core.transfer.*
import io.moduhome.app.feature.home.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class JourneyJsonTest {
    private val now = Instant.parse("2026-10-29T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val home = NavigationPoint("운전자 집", 37.50, 127.0)
    private val pickup = NavigationPoint("커피 & 픽업 ☕", 37.52, 127.02, "매장 / 입구 #2", "poi/1", "sub-1", "provider")
    private val groceries = NavigationPoint("장보기", 37.53, 127.03)
    private val proposal = ErrandProposal("proposal-001", 2, now, now + 3_600_000,
        VisitKind.PICKUP, pickup, 12, 2, false, now + 1_800_123)
    private val receipt = HomeProposalAcceptance(proposal, now)
    private val draft = JourneyDraft(origin = "현재 차량 위치", dateEpochDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay(),
        budget = 240, pickup = VisitDraft(place = pickup.name, stay = "12", walking = "2", ready = true),
        groceries = VisitDraft(place = groceries.name, stay = "5"), navigationPlaces = NavigationPlaces(home, pickup, groceries), home = home.name)
    private val state get() = HomeUiState(draft, acceptedProposals = listOf(receipt))
    private val fallback = JourneyDraft(home = "복구용 집")
    private fun encoded(value: HomeUiState = state) = requireNotNull(JourneyJson.encode(value))
    private fun decoded(json: JSONObject, at: Long = now + 1) = JourneyJson.decode(json.toString(), fallback, at)

    @Test fun roundTripPreservesEveryOriginalFieldAndLaterManualEditsSeparately() {
        val restored = JourneyJson.decode(encoded(), fallback, now + 1)
        assertEquals(state, restored)
        assertEquals(proposal, restored.acceptedProposals.single().proposal)
        assertFalse(restored.acceptedProposals.single().proposal.ready)
        assertTrue(restored.draft.pickup.ready)
        assertEquals(2, restored.acceptedProposals.single().proposal.walkingMinutesOneWay)
        assertEquals(now + 1_800_123, restored.acceptedProposals.single().proposal.deadlineAtMillis)
        assertEquals(pickup, restored.draft.navigationPlaces.pickup)
    }

    @Test fun schemaOneWithoutReceiptFieldKeepsTheExistingJourney() {
        val old = JSONObject(encoded(state.copy(acceptedProposals = emptyList()))).apply { remove("acceptedProposals") }
        assertEquals(state.copy(acceptedProposals = emptyList()), decoded(old))
    }

    @Test fun compareReviewActiveAndCompleteRetainTheirProgressAndAcceptanceHistory() {
        val start = state
        val compared = start.copy(progress = start.progress.compare(start.plans))
        val reviewed = compared.copy(progress = compared.progress.review(compared.plans))
        val active = reviewed.copy(progress = reviewed.progress.start(reviewed.plans, now))
        val done = active.plans.first { it.id == active.progress.selectedPlanId }.visits.fold(active) { current, visit ->
            current.copy(progress = current.progress.completeVisit(current.plans, visit.visit.kind, now + 1))
        }.let { it.copy(progress = it.progress.finish(it.plans, now + 10)) }
        assertEquals(JourneyStage.COMPLETE, done.progress.stage)
        for (current in listOf(compared, reviewed, active, done)) {
            assertEquals(current, JourneyJson.decode(encoded(current), fallback, now + 100))
        }
    }

    @Test fun corruptRoutingAndInvalidSelectedPlanPreserveIndependentReceiptsAndInput() {
        val withBadRoute = JSONObject(encoded()).put("routing", JSONObject().put("schema", -1))
        val badPlan = JSONObject(encoded()).put("stage", "ACTIVE").put("selected", "missing")
        for (raw in listOf(withBadRoute, badPlan)) {
            val restored = decoded(raw)
            assertEquals(state.draft, restored.draft)
            assertEquals(state.acceptedProposals, restored.acceptedProposals)
            assertEquals(JourneyProgress(), restored.progress)
            assertNull(restored.routing)
        }
    }

    @Test fun aRestoredAddressItineraryRebuildsTheNextWindowWithoutAStoredRouteApproval() {
        val addresses = state.copy(draft = draft.copy(navigationPlaces = NavigationPlaces(home,
            pickup.copy(placeId = ""), groceries.copy(placeId = ""))))
        val selected = addresses.plans.single { it.visits.first().visit.kind == VisitKind.PICKUP }
        val active = addresses.copy(progress = JourneyProgress().compare(addresses.plans).select(selected.id, addresses.plans)
            .review(addresses.plans).start(addresses.plans, now))
        val firstDone = active.copy(progress = active.progress.completeVisit(active.plans, VisitKind.PICKUP, now + 1))
        val restored = JourneyJson.decode(encoded(firstDone), fallback, now + 2)
        assertEquals(firstDone, restored)
        assertEquals(groceries.copy(placeId = ""), restored.navigationWindow?.destination)
        assertEquals(emptyList<NavigationPoint>(), restored.navigationWindow?.waypoints)
        val allDone = restored.copy(progress = restored.progress.completeVisit(restored.plans, VisitKind.GROCERIES, now + 3))
        assertEquals(home, JourneyJson.decode(encoded(allDone), fallback, now + 4).navigationWindow?.destination)
        assertFalse(JSONObject(encoded(firstDone)).has("approval"))
    }

    @Test fun expiredAcceptanceRemainsHistoricalEvidence() {
        val restored = JourneyJson.decode(encoded(), fallback, proposal.expiresAtMillis + 1)
        assertEquals(listOf(receipt), restored.acceptedProposals)
        val originalInbox = JourneyProposalInbox.restore(listOf(ProposalInboxEntry(proposal)))
        assertEquals(ProposalReviewStatus.APPROVED, restored.reconcileProposalInbox(originalInbox).entries.single().status)
    }

    @Test fun duplicateOrOversizedReceiptHistoryCannotBeEncodedOrRestored() {
        assertNull(JourneyJson.encode(state.copy(acceptedProposals = listOf(receipt, receipt))))
        val raw = JSONObject(encoded())
        val repeated = raw.getJSONArray("acceptedProposals").getJSONObject(0)
        raw.getJSONArray("acceptedProposals").put(repeated)
        assertTrue(decoded(raw).acceptedProposals.isEmpty())
        val tooMany = state.copy(acceptedProposals = (0..JourneyProposalInbox.MAX_ENTRIES).map {
            HomeProposalAcceptance(proposal.copy(id = "receipt-$it"), now)
        })
        assertNull(JourneyJson.encode(tooMany))
    }

    @Test fun mismatchedCreatedTimeOrMalformedProposalCannotBecomeAnApprovalReceipt() {
        for (field in listOf("createdAt", "link")) {
            val raw = JSONObject(encoded())
            val record = raw.getJSONArray("acceptedProposals").getJSONObject(0)
            if (field == "createdAt") record.put(field, now + 1) else record.put(field, "not-a-proposal")
            assertTrue(decoded(raw).acceptedProposals.isEmpty())
        }
    }

    @Test fun futureExpiredAtApprovalAndNonIntegerTimestampsAreRejected() {
        for ((field, value) in listOf(
            "appliedAt" to now + 10_000, "appliedAt" to proposal.expiresAtMillis,
            "appliedAt" to now.toString(), "appliedAt" to now.toDouble() + 0.5, "createdAt" to JSONObject.NULL,
        )) {
            val raw = JSONObject(encoded())
            raw.getJSONArray("acceptedProposals").getJSONObject(0).put(field, value)
            assertTrue(decoded(raw).acceptedProposals.isEmpty())
            assertEquals(JourneyStage.INPUT, decoded(raw).progress.stage)
        }
    }

    @Test fun negativeClockMalformedJsonAndUnknownSchemaReturnTheFallback() {
        assertEquals(HomeUiState(fallback), JourneyJson.decode(encoded(), fallback, -1))
        assertEquals(HomeUiState(fallback), JourneyJson.decode("not-json", fallback, now))
        assertEquals(HomeUiState(fallback), decoded(JSONObject(encoded()).put("schema", 99)))
    }

    @Test fun utf8ByteLimitBoundsBothEncodingAndRestoration() {
        val large = state.copy(draft = draft.copy(origin = "가".repeat(90_000)))
        assertNull(JourneyJson.encode(large))
        val raw = JSONObject(encoded())
        raw.getJSONObject("draft").put("origin", "가".repeat(90_000))
        assertEquals(HomeUiState(fallback), decoded(raw))
    }

    @Test fun validRoutedComparisonRetainsItsOwnLegsAndReceiptHistory() {
        val origin = NavigationPoint(draft.origin, 37.51, 127.01)
        fun estimate(points: List<NavigationPoint>, seconds: Double): RouteEstimate =
            (RouteEstimate.fromObservation(points, List(points.size - 1) { ObservedRouteLeg(seconds, seconds * 10) },
                points.map { SnappedRoutePoint(it.latitude, it.longitude, 0.0) },
                seconds * (points.size - 1), seconds * 10 * (points.size - 1), now, "map-2026-10-29") as RouteEstimateResult.Success).estimate
        val direct = estimate(listOf(origin, home), 300.0)
        val routes = mapOf(
            "PICKUP>GROCERIES" to estimate(listOf(origin, pickup, groceries, home), 61.0),
            "GROCERIES>PICKUP" to estimate(listOf(origin, groceries, pickup, home), 121.0),
        )
        val routed = requireNotNull(RoutedJourneyEstimates.create(draft, origin, direct, routes))
        val compared = state.copy(routing = routed).let { it.copy(progress = it.progress.compare(it.plans)) }
        val restored = JourneyJson.decode(encoded(compared), fallback, now + 100)
        assertEquals(compared.progress, restored.progress)
        assertEquals(compared.plans, restored.plans)
        val restoredRoutes = requireNotNull(restored.routing).routes
        assertEquals(routes.keys, restoredRoutes.keys)
        for ((id, expected) in routes) {
            val actual = restoredRoutes.getValue(id)
            assertEquals(expected.points, actual.points)
            assertEquals(expected.legs, actual.legs)
            assertEquals(expected.snappedPoints, actual.snappedPoints)
            assertEquals(expected.queriedAtEpochMillis, actual.queriedAtEpochMillis)
            assertEquals(expected.dataVersion, actual.dataVersion)
        }
        assertEquals(compared.acceptedProposals, restored.acceptedProposals)
    }
}
