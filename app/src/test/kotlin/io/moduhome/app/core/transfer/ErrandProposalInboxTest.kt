package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrandProposalInboxTest {
    private val now = 1_800_000_000_000L
    private fun proposal(id: String = "proposal-001", revision: Int = 1) = ErrandProposal(
        id, revision, now, now + 60 * 60 * 1000, VisitKind.PICKUP,
        NavigationPoint("픽업 매장", 37.5, 127.0), 5, ready = true,
    )

    private fun receive(inbox: ErrandProposalInbox = ErrandProposalInbox.empty(), proposal: ErrandProposal = proposal()):
        ProposalReceiveResult.Pending {
        val result = inbox.receive(proposal, now)
        assertTrue(result is ProposalReceiveResult.Pending)
        return result as ProposalReceiveResult.Pending
    }

    @Test fun receivingAProposalOnlyStagesItForReview() {
        val original = ErrandProposalInbox.empty()
        val pending = receive(original)
        assertTrue(original.entries.isEmpty())
        assertEquals(ProposalReviewStatus.PENDING, pending.entry.status)
        assertEquals(listOf(pending.entry), pending.inbox.entries)
    }

    @Test fun duplicateDeliveryDoesNotCreateAnotherPendingReview() {
        val pending = receive()
        assertEquals(ProposalReceiveResult.Duplicate, pending.inbox.receive(proposal(), now))
        assertEquals(1, pending.inbox.entries.size)
    }

    @Test fun conflictingContentAtTheSameRevisionIsRejected() {
        val pending = receive()
        assertTrue(pending.inbox.receive(proposal().copy(stayMinutes = 15), now) is ProposalReceiveResult.Rejected)
        assertEquals(5, pending.inbox.entries.single().proposal.stayMinutes)
    }

    @Test fun olderRevisionCannotReplaceTheLatestSuggestion() {
        val pending = receive(proposal = proposal(revision = 2))
        assertTrue(pending.inbox.receive(proposal(), now) is ProposalReceiveResult.Rejected)
    }

    @Test fun newerRevisionRequiresReviewEvenWhenTheOldOneWasApproved() {
        val pending = receive()
        val approved = pending.inbox.approve("proposal-001", 1, now) as ProposalApprovalResult.Approved
        val next = receive(approved.inbox, proposal(revision = 2).copy(ready = false))
        assertEquals(1, next.inbox.entries.size)
        assertEquals(ProposalReviewStatus.PENDING, next.entry.status)
        assertFalse(next.entry.proposal.ready)
    }

    @Test fun anOutdatedReviewCannotApproveARevisedSuggestion() {
        val pending = receive()
        val revised = receive(pending.inbox, proposal(revision = 2))
        assertTrue(revised.inbox.approve("proposal-001", 1, now) is ProposalApprovalResult.Rejected)
    }

    @Test fun approvalReturnsOnlyTheExactReviewedProposalAndCannotBeRepeated() {
        val pending = receive()
        val approved = pending.inbox.approve("proposal-001", 1, now) as ProposalApprovalResult.Approved
        assertEquals(proposal(), approved.proposal)
        assertEquals(ProposalReviewStatus.APPROVED, approved.inbox.entries.single().status)
        assertTrue(approved.inbox.approve("proposal-001", 1, now) is ProposalApprovalResult.Rejected)
        assertEquals(ProposalReceiveResult.Duplicate, approved.inbox.receive(proposal(), now))
    }

    @Test fun restoredReviewDecisionsStillPreventDuplicateApproval() {
        val pending = receive()
        val approved = pending.inbox.approve("proposal-001", 1, now) as ProposalApprovalResult.Approved
        val restored = ErrandProposalInbox.restore(approved.inbox.entries)
        assertEquals(approved.inbox, restored)
        assertEquals(ProposalReceiveResult.Duplicate, restored.receive(proposal(), now))
        assertTrue(restored.approve("proposal-001", 1, now) is ProposalApprovalResult.Rejected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun restoredInboxCannotContainTwoRevisionsOfTheSameProposal() {
        ErrandProposalInbox.restore(listOf(ProposalInboxEntry(proposal()), ProposalInboxEntry(proposal(revision = 2))))
    }

    @Test fun aProposalThatExpiresWhileReviewingCannotBeApproved() {
        val pending = receive()
        assertTrue(pending.inbox.approve("proposal-001", 1, proposal().expiresAtMillis) is ProposalApprovalResult.Rejected)
    }

    @Test fun aPassedDeadlineIsRecheckedWhenApproving() {
        val pending = receive(proposal = proposal().copy(deadlineAtMillis = now + 60_000))
        assertTrue(pending.inbox.approve("proposal-001", 1, now + 60_001) is ProposalApprovalResult.Rejected)
        assertTrue(pending.inbox.approve("proposal-001", 1, now + 60_000) is ProposalApprovalResult.Approved)
    }

    @Test fun decliningDoesNotMakeTheSuggestionApprovedOrCreateAnotherOnReplay() {
        val pending = receive()
        val declined = pending.inbox.decline("proposal-001", 1)
        assertEquals(ProposalReviewStatus.DECLINED, declined.entries.single().status)
        assertTrue(declined.approve("proposal-001", 1, now) is ProposalApprovalResult.Rejected)
        assertEquals(ProposalReceiveResult.Duplicate, declined.receive(proposal(), now))
    }

    @Test fun pendingInboxIsBoundedAndExpiredEntriesAreReleasedOnReceive() {
        var inbox = ErrandProposalInbox.empty()
        repeat(ErrandProposalInbox.MAX_ENTRIES) { index ->
            inbox = receive(inbox, proposal(id = "proposal-${index.toString().padStart(3, '0')}" )).inbox
        }
        assertTrue(inbox.receive(proposal("proposal-extra"), now) is ProposalReceiveResult.Rejected)
        val later = proposal("proposal-later").copy(createdAtMillis = now + 3_600_000, expiresAtMillis = now + 7_200_000)
        val accepted = inbox.receive(later, now + 3_600_000) as ProposalReceiveResult.Pending
        assertEquals(1, accepted.inbox.entries.size)
    }

    @Test fun aClockDifferenceOfFiveMinutesIsAllowedButMoreIsRejected() {
        val allowed = proposal().copy(createdAtMillis = now + 300_000)
        assertTrue(ErrandProposalInbox.empty().receive(allowed, now) is ProposalReceiveResult.Pending)
        assertTrue(ErrandProposalInbox.empty().receive(allowed.copy(createdAtMillis = now + 300_001), now)
            is ProposalReceiveResult.Rejected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCoordinatePlaceholderCannotBecomeANavigationSuggestion() {
        proposal().copy(place = NavigationPoint("좌표 없음", 0.0, 0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unpairedSurrogatesCannotBeChangedSilentlyDuringEncoding() {
        proposal().copy(place = NavigationPoint("매장\uD800", 37.5, 127.0))
    }
}
