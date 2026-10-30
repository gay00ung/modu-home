package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.*
import io.moduhome.app.core.shared.*
import io.moduhome.app.data.shared.SharedJourneyJson
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SharedDropoffProposalTest {
    private val now = Instant.parse("2026-10-30T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val proposal = DropoffProposal("dropoff-001", 1, now, now + 3_600_000,
        NavigationPoint("새 하차지", 37.56, 126.98), "친구 A", now + 1_800_000)
    private val original = SharedJourneySession(SharedJourneyDraft(
        driverHome = NavigationPoint("운전자 집", 37.5, 127.0),
        passengers = listOf(SharedPassengerDraft("p1", "동승자 1", NavigationPoint("원래 하차지", 37.51, 127.01))),
        errand = SharedErrandDraft(VisitKind.GROCERIES, NavigationPoint("마트", 37.52, 127.02), 15),
        allowedAdditionalMinutes = 60,
    ))
    private fun inbox(value: DropoffProposal = proposal) = JourneyProposalInbox.restore(listOf(ProposalInboxEntry(value)))
    private fun review(session: SharedJourneySession = original, value: DropoffProposal = proposal) =
        (session.reviewProposal(inbox(value), value.id, value.revision, now, zone) as SharedProposalReviewResult.Ready).review

    @Test fun receivingAndSelectingNeverModifyTheDraftAndApprovalNeedsBothChecks() {
        val preview = review()
        assertEquals(original, preview.session)
        assertTrue(original.approveProposal(preview, inbox(), now, zone) is SharedProposalApplyResult.Rejected)
        val chosen = preview.forPassenger("p1")!!
        assertTrue(original.approveProposal(chosen, inbox(), now, zone) is SharedProposalApplyResult.Rejected)
        assertEquals("원래 하차지", original.draft.passengers.single().dropoff!!.name)
        assertEquals(ProposalReviewStatus.PENDING, inbox().entries.single().status)
    }

    @Test fun explicitApprovalChangesOnlyTheSelectedPassengerAndPreservesTheOtherConditions() {
        val preview = review().forPassenger("p1")!!.confirmDoorstep(true)!!
        val result = original.approveProposal(preview, inbox(), now + 1, zone) as SharedProposalApplyResult.Applied
        val person = result.session.draft.passengers.single()
        assertEquals(proposal.place, person.dropoff)
        assertEquals(proposal.passengerName, person.name)
        assertEquals(proposal.deadlineAtMillis, person.latestArrivalAtMillis)
        assertTrue(person.doorstepDropoffConfirmed)
        assertEquals(now + 1, person.doorstepConfirmedAtMillis)
        assertEquals(original.draft.driverHome, result.session.draft.driverHome)
        assertEquals(original.draft.errand, result.session.draft.errand)
        assertEquals(60, result.session.draft.allowedAdditionalMinutes)
        assertEquals(ProposalReviewStatus.APPROVED, result.inbox.entries.single().status)
    }

    @Test fun addingASecondPassengerKeepsTheFirstAndCannotCreateAThird() {
        val preview = review()
        val secondId = preview.passengerChoices.last().id
        val first = original.approveProposal(preview.forPassenger(secondId)!!.confirmDoorstep(true)!!, inbox(), now, zone)
            as SharedProposalApplyResult.Applied
        assertEquals(2, first.session.draft.passengers.size)
        assertEquals(original.draft.passengers.single(), first.session.draft.passengers.first())
        val next = proposal.copy(id = "dropoff-002", passengerName = "친구 B")
        val nextReview = review(first.session, next)
        assertEquals(2, nextReview.passengerChoices.size)
        assertNull(nextReview.forPassenger("third-person"))
        assertFalse(nextReview.forPassenger("p1")!!.doorstepConfirmed)
    }

    @Test fun repeatingTheSamePassengerChoiceKeepsConfirmationButSwitchingPassengersClearsIt() {
        val selected = review().forPassenger("p1")!!.confirmDoorstep(true)!!
        assertSame(selected, selected.forPassenger("p1"))
        assertTrue(selected.forPassenger("p1")!!.doorstepConfirmed)
        val other = selected.passengerChoices.last().id
        assertFalse(selected.forPassenger(other)!!.doorstepConfirmed)
    }

    @Test fun aChangedDraftRevisionOrArrivalInvalidatesTheReview() {
        val preview = review().forPassenger("p1")!!.confirmDoorstep(true)!!
        val changed = original.copy(draft = original.draft.copy(allowedAdditionalMinutes = 30))
        assertTrue(changed.approveProposal(preview, inbox(), now + 1, zone) is SharedProposalApplyResult.Rejected)
        assertTrue(original.approveProposal(preview, inbox(proposal.copy(revision = 2)), now + 1, zone) is SharedProposalApplyResult.Rejected)
        assertTrue(original.approveProposal(preview, inbox(), proposal.deadlineAtMillis!! + 1, zone) is SharedProposalApplyResult.Rejected)
    }

    @Test fun aPhoneCorrectionInvalidatesTheOldReviewAndNeedsFreshPassengerConfirmation() {
        val phone = CompanionDropoffDraft.forAddress(proposal.place, now, zone)
        val first = (phone.createProposal(now, zone) as CompanionDropoffResult.Created).proposal
        val oldReview = review(value = first).forPassenger("p1")!!.confirmDoorstep(true)!!
        val corrected = (phone.withIssuedProposal(first).copy(passengerName = "수정한 이름")
            .createProposal(now + 1, zone) as CompanionDropoffResult.Created).proposal
        val pending = (inbox(first).receive(corrected, now + 1) as ProposalReceiveResult.Pending).inbox
        assertTrue(original.approveProposal(oldReview, pending, now + 2, zone) is SharedProposalApplyResult.Rejected)
        val fresh = (original.reviewProposal(pending, corrected.id, corrected.revision, now + 2, zone)
            as SharedProposalReviewResult.Ready).review
        assertNull(fresh.passengerId)
        assertFalse(fresh.doorstepConfirmed)
        assertTrue(original.approveProposal(fresh.forPassenger("p1")!!.confirmDoorstep(true)!!, pending, now + 2, zone)
            is SharedProposalApplyResult.Applied)
    }

    @Test fun savedDropoffApprovalRestoresItsTargetAndRepairsOnlyItsOwnPendingReceipt() {
        val applied = original.approveProposal(review().forPassenger("p1")!!.confirmDoorstep(true)!!, inbox(), now, zone)
            as SharedProposalApplyResult.Applied
        val restored = SharedJourneyJson.decode(SharedJourneyJson.encode(applied.session)!!, now + 1)
        assertEquals(applied.session, restored)
        assertEquals("p1", restored.acceptedProposals.single().passengerId)
        assertEquals(ProposalReviewStatus.APPROVED, restored.reconcileProposalInbox(inbox()).entries.single().status)
        val newer = proposal.copy(revision = 2)
        assertEquals(ProposalReviewStatus.PENDING, restored.reconcileProposalInbox(inbox(newer)).entries.single().status)
    }

    @Test fun twoApprovedDropoffsAndAnErrandProduceSixOrdersWithTheArrivalConstraintApplied() {
        val first = original.approveProposal(review().forPassenger("p1")!!.confirmDoorstep(true)!!, inbox(), now, zone)
            as SharedProposalApplyResult.Applied
        val other = proposal.copy(id = "dropoff-002", passengerName = "친구 B", deadlineAtMillis = null,
            place = NavigationPoint("두 번째 하차지", 37.57, 126.99))
        val secondReview = review(first.session, other)
        val next = first.session.approveProposal(secondReview.forPassenger(secondReview.passengerChoices.last().id)!!
            .confirmDoorstep(true)!!, inbox(other), now, zone) as SharedProposalApplyResult.Applied
        val request = next.session.draft.validate(NavigationPoint("출발", 37.6, 127.1), now, zone).request!!
        val places = listOf(request.origin, request.home) + request.passengers.flatMap { p -> p.candidates.map { it.place } } + request.errand!!.place
        val evidence = TravelEvidence(TravelEstimateKind.USER_INPUT, "단위 테스트의 구간 시간")
        val legs = places.flatMap { from -> places.filter { it.id != from.id }.map { to ->
            DrivingLegKey(from.id, to.id) to SharedDrivingEstimate(10, evidence)
        } }.toMap()
        val plans = SharedJourneyPlanner().compare(request.copy(drivingEstimates = legs))
        assertEquals(6, plans.size)
        assertTrue(plans.any { it.isValid })
        assertTrue(plans.any { !it.isValid })
        assertTrue(plans.all { it.passengerArrivals.size == 2 })
    }
}
