package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.SharedJourneyProgress
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.JourneyProposalInbox
import io.moduhome.app.core.transfer.ProposalInboxEntry
import io.moduhome.app.core.transfer.ProposalReviewStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SharedProposalMappingTest {
    private val now = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val proposal = ErrandProposal("proposal-001", 2, now, now + 3_600_000, VisitKind.PICKUP,
        NavigationPoint("빵 & 커피 ☕", 37.51, 127.02, "입구 2층", "poi-01", "sub-1", "place-provider"),
        12, 2, false, now + 1_800_123)
    private val draft = SharedJourneyDraft(
        driverHome = NavigationPoint("운전자 집", 37.5, 127.0),
        passengers = listOf(SharedPassengerDraft("p1", "동승자 1", NavigationPoint("집 앞", 37.52, 127.03),
            DropoffVerification(AccessCheck.SUITABLE, "직접 확인", now - 1000),
            latestArrivalAtMillis = now + 7_200_000, maximumOnboardMinutes = 120)),
        errand = SharedErrandDraft(VisitKind.GROCERIES, NavigationPoint("기존 장보기", 37.53, 127.04),
            15, 1, true, now + 3_600_000),
        allowedAdditionalMinutes = 60,
    )
    private val session get() = SharedJourneySession(draft)
    private fun inbox(value: ErrandProposal = proposal, status: ProposalReviewStatus = ProposalReviewStatus.PENDING) =
        JourneyProposalInbox.restore(listOf(ProposalInboxEntry(value, status)))
    private fun review(current: SharedJourneySession = session, value: ErrandProposal = proposal, at: Long = now): SharedProposalReview =
        (current.reviewProposal(inbox(value), value.id, value.revision, at, zone) as SharedProposalReviewResult.Ready).review

    @Test fun previewChangesNothingAndApprovalReplacesAnyKindWhilePreservingEveryCondition() {
        val initial = session
        val received = inbox()
        val preview = review(initial)
        assertEquals(draft, initial.draft)
        assertTrue(initial.acceptedProposals.isEmpty())
        assertEquals(ProposalReviewStatus.PENDING, received.entries.single().status)
        val result = initial.approveProposal(preview, received, now + 1, zone) as SharedProposalApplyResult.Applied
        val applied = result.session
        assertEquals(draft.driverHome, applied.draft.driverHome)
        assertEquals(draft.passengers, applied.draft.passengers)
        assertEquals(draft.allowedAdditionalMinutes, applied.draft.allowedAdditionalMinutes)
        assertEquals(SharedErrandDraft(proposal.kind, proposal.place, 12, 2, false, proposal.deadlineAtMillis), applied.draft.errand)
        assertEquals(SharedJourneyProgress(), applied.progress)
        assertNull(applied.routing)
        assertEquals(listOf(SharedProposalAcceptance(proposal, now + 1)), applied.acceptedProposals)
        assertEquals(ProposalReviewStatus.APPROVED, result.inbox.entries.single().status)
        assertEquals(ProposalReviewStatus.PENDING, received.entries.single().status)
        val request = applied.draft.validate(NavigationPoint("현재 위치", 37.54, 127.05), now + 1, zone).request!!
        val visit = requireNotNull(request.errand).visit
        assertFalse(visit.ready)
        assertEquals(2, visit.walkingMinutesOneWay)
    }

    @Test fun onlyPendingProposalsOnTheInputStageCanBeReviewed() {
        for (stage in JourneyStage.entries.filter { it != JourneyStage.INPUT }) {
            val current = session.copy(progress = SharedJourneyProgress(stage = stage))
            assertTrue(current.reviewProposal(inbox(), proposal.id, proposal.revision, now, zone) is SharedProposalReviewResult.Rejected)
        }
        for (status in listOf(ProposalReviewStatus.APPROVED, ProposalReviewStatus.DECLINED)) {
            assertTrue(session.reviewProposal(inbox(status = status), proposal.id, proposal.revision, now, zone) is SharedProposalReviewResult.Rejected)
        }
        assertTrue(session.reviewProposal(inbox(), proposal.id, 1, now, zone) is SharedProposalReviewResult.Rejected)
        assertTrue(session.reviewProposal(JourneyProposalInbox.empty(), proposal.id, 2, now, zone) is SharedProposalReviewResult.Rejected)
    }

    @Test fun approvalRejectsChangedInputActiveStageAndAChangedOriginalProposal() {
        val initial = session
        val preview = review(initial)
        val changed = initial.copy(draft = draft.copy(allowedAdditionalMinutes = 30))
        val active = initial.copy(progress = SharedJourneyProgress(stage = JourneyStage.ACTIVE))
        for (current in listOf(changed, active)) {
            assertTrue(current.approveProposal(preview, inbox(), now + 1, zone) is SharedProposalApplyResult.Rejected)
        }
        for (different in listOf(proposal.copy(stayMinutes = 13), proposal.copy(revision = 3),
            proposal.copy(place = proposal.place.copy(address = "다른 입구")))) {
            assertTrue(initial.approveProposal(preview, inbox(different), now + 1, zone) is SharedProposalApplyResult.Rejected)
        }
        assertTrue(initial.approveProposal(preview, inbox(status = ProposalReviewStatus.APPROVED), now + 1, zone) is SharedProposalApplyResult.Rejected)
        assertTrue(initial.approveProposal(preview, JourneyProposalInbox.empty(), now + 1, zone) is SharedProposalApplyResult.Rejected)
    }

    @Test fun reviewsDefensivelyCopyListsSoLaterMutationCannotChangeTheApprovedInput() {
        val passengers = draft.passengers.toMutableList()
        val earlier = proposal.copy(id = "previous-001")
        val accepted = mutableListOf(SharedProposalAcceptance(earlier, now))
        val current = session.copy(draft = draft.copy(passengers = passengers), acceptedProposals = accepted)
        val preview = review(current)
        passengers.clear()
        accepted.clear()
        assertEquals(1, preview.session.draft.passengers.size)
        assertEquals(1, preview.session.acceptedProposals.size)
        assertTrue(current.approveProposal(preview, inbox(), now + 1, zone) is SharedProposalApplyResult.Rejected)
    }

    @Test fun proposalExpiryAndCompletionDeadlineAreRecheckedAtFinalApproval() {
        val preview = review()
        assertTrue(session.approveProposal(preview, inbox(), proposal.expiresAtMillis, zone) is SharedProposalApplyResult.Rejected)
        val short = proposal.copy(deadlineAtMillis = now + 1000)
        val shortReview = review(value = short)
        assertTrue(session.approveProposal(shortReview, inbox(short), now + 1000, zone) is SharedProposalApplyResult.Applied)
        assertTrue(session.approveProposal(shortReview, inbox(short), now + 1001, zone) is SharedProposalApplyResult.Rejected)
    }

    @Test fun aFortyEightHourTransferCannotIntroduceADayAfterTomorrowSharedDeadline() {
        val tooLate = proposal.copy(deadlineAtMillis = now + 36 * 3_600_000L)
        assertTrue(session.reviewProposal(inbox(tooLate), tooLate.id, tooLate.revision, now, zone) is SharedProposalReviewResult.Rejected)
        val tomorrow = proposal.copy(deadlineAtMillis = now + 20 * 3_600_000L)
        val result = session.approveProposal(review(value = tomorrow), inbox(tomorrow), now + 1, zone) as SharedProposalApplyResult.Applied
        assertEquals(tomorrow.deadlineAtMillis, result.session.draft.errand!!.deadlineAtMillis)
    }

    @Test fun anIdenticalErrandIsRejectedButAbsentDeadlinesAndNotReadyValuesReplaceOldConditions() {
        val same = SharedErrandDraft(proposal.kind, proposal.place, proposal.stayMinutes, proposal.walkingMinutesOneWay,
            proposal.ready, proposal.deadlineAtMillis)
        val duplicate = session.copy(draft = draft.copy(errand = same))
        assertTrue(duplicate.reviewProposal(inbox(), proposal.id, proposal.revision, now, zone) is SharedProposalReviewResult.Rejected)
        val noDeadline = proposal.copy(deadlineAtMillis = null)
        val applied = session.approveProposal(review(value = noDeadline), inbox(noDeadline), now, zone) as SharedProposalApplyResult.Applied
        assertNull(applied.session.draft.errand!!.deadlineAtMillis)
        assertFalse(applied.session.draft.errand!!.ready)
    }

    @Test fun acceptedHistoryRejectsEqualOrLowerRevisionsAndKeepsOnlyTheLatestAcceptedRevision() {
        val accepted = SharedProposalAcceptance(proposal, now)
        val current = session.copy(acceptedProposals = listOf(accepted))
        for (revision in listOf(1, 2)) {
            val repeated = proposal.copy(revision = revision)
            assertTrue(current.reviewProposal(inbox(repeated), repeated.id, revision, now, zone) is SharedProposalReviewResult.Rejected)
        }
        val revised = proposal.copy(revision = 3)
        val applied = current.approveProposal(review(current, revised), inbox(revised), now + 1, zone) as SharedProposalApplyResult.Applied
        assertEquals(listOf(SharedProposalAcceptance(revised, now + 1)), applied.session.acceptedProposals)
        assertTrue(applied.session.resetToInput(draft).reviewProposal(inbox(revised), revised.id, 3, now + 2, zone) is SharedProposalReviewResult.Rejected)
    }

    @Test fun expiredHistoryIsPrunedOnlyWhenANewProposalIsActuallyAccepted() {
        val old = proposal.copy(id = "previous-001", createdAtMillis = now - 10_800_000,
            expiresAtMillis = now - 3_600_000, deadlineAtMillis = null)
        val record = SharedProposalAcceptance(old, now - 7_200_000)
        val current = session.copy(acceptedProposals = listOf(record))
        val preview = review(current)
        assertEquals(listOf(record), current.acceptedProposals)
        assertEquals(listOf(record), preview.session.acceptedProposals)
        val applied = current.approveProposal(preview, inbox(), now + 1, zone) as SharedProposalApplyResult.Applied
        assertEquals(listOf(SharedProposalAcceptance(proposal, now + 1)), applied.session.acceptedProposals)
    }

    @Test fun historyCapacityNeverEvictsUnexpiredReceiptsAndStillAllowsALaterRevision() {
        val history = List(JourneyProposalInbox.MAX_ENTRIES) { index ->
            SharedProposalAcceptance(proposal.copy(id = "accepted-${index.toString().padStart(3, '0')}"), now)
        }
        val current = session.copy(acceptedProposals = history)
        assertTrue(current.reviewProposal(inbox(), proposal.id, proposal.revision, now, zone) is SharedProposalReviewResult.Rejected)
        val replacement = (history.first().proposal as ErrandProposal).copy(revision = 3)
        val applied = current.approveProposal(review(current, replacement), inbox(replacement), now + 1, zone) as SharedProposalApplyResult.Applied
        assertEquals(20, applied.session.acceptedProposals.size)
        assertEquals(1, applied.session.acceptedProposals.count { it.proposal.id == replacement.id })
        assertEquals(replacement, applied.session.acceptedProposals.last().proposal)
    }

    @Test fun finalApprovalRejectsClockRollbackAndAChangedTimeZone() {
        val preview = review()
        assertTrue(session.approveProposal(preview, inbox(), now - 1, zone) is SharedProposalApplyResult.Rejected)
        assertTrue(session.approveProposal(preview, inbox(), now, ZoneId.of("UTC")) is SharedProposalApplyResult.Rejected)
    }

    @Test fun durableAcceptanceRepairsAnExpiredPendingInboxWithoutChangingTheJourney() {
        val old = proposal.copy(createdAtMillis = now - 10_800_000, expiresAtMillis = now - 3_600_000, deadlineAtMillis = null)
        val current = session.copy(acceptedProposals = listOf(SharedProposalAcceptance(old, now - 7_200_000)))
        val pending = inbox(old)
        val repaired = current.reconcileProposalInbox(pending)
        assertEquals(ProposalReviewStatus.APPROVED, repaired.entries.single().status)
        assertEquals(ProposalReviewStatus.PENDING, pending.entries.single().status)
        assertEquals(draft, current.draft)
        assertEquals(SharedJourneyProgress(), current.progress)
        assertSame(repaired, current.reconcileProposalInbox(repaired))
    }

    @Test fun reconciliationDoesNotApproveHigherRevisionsChangedPayloadsOrDeclinedEntries() {
        val current = session.copy(acceptedProposals = listOf(SharedProposalAcceptance(proposal, now)))
        for (different in listOf(proposal.copy(revision = 3), proposal.copy(walkingMinutesOneWay = 3))) {
            val pending = inbox(different)
            assertSame(pending, current.reconcileProposalInbox(pending))
        }
        val declined = inbox(status = ProposalReviewStatus.DECLINED)
        assertSame(declined, current.reconcileProposalInbox(declined))
    }
}
