package io.moduhome.app.data.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.*
import org.junit.Assert.*
import org.junit.Test

class RelayProposalReceiverTest {
    private val now = 1_793_000_000_000L
    private val messageId = "45155591-fd06-4e25-b112-13d56a374ea7"
    private fun proposal(id: String = "proposal-001", revision: Int = 1) = ErrandProposal(
        id, revision, now, now + 3_600_000, VisitKind.PICKUP,
        NavigationPoint("픽업 매장", 37.5, 127.0), 12, 2, false, now + 1_800_000,
    )
    private fun message(proposal: ErrandProposal = proposal()) = RelayMessage(messageId, ProposalLinkCodec.encode(proposal))
    private fun inbox(proposal: ErrandProposal = proposal()) =
        (JourneyProposalInbox.empty().receive(proposal, now) as ProposalReceiveResult.Pending).inbox

    @Test fun onlyDurablySavedPendingProposalGetsStoredReceipt() {
        val initial = JourneyProposalInbox.empty()
        var saved: JourneyProposalInbox? = null
        val result = receiveRelayProposal(initial, message(), now) { saved = it; true }
        assertEquals(RelayDeliveryStatus.STORED, result.acknowledgement)
        assertEquals(saved, result.inbox)
        assertEquals(ProposalReviewStatus.PENDING, result.inbox.entries.single().status)
        assertEquals(proposal(), result.inbox.entries.single().proposal)
        assertTrue(result.newProposal)
        assertTrue(initial.entries.isEmpty())
    }

    @Test fun failedStorageKeepsOriginalStateAndLeavesMessageQueued() {
        val initial = JourneyProposalInbox.empty()
        val result = receiveRelayProposal(initial, message(), now) { false }
        assertSame(initial, result.inbox)
        assertNull(result.acknowledgement)
        assertFalse(result.newProposal)
    }

    @Test fun duplicateRequiresDurableConfirmationAndKeepsReviewDecision() {
        val pending = inbox()
        val approved = (pending.approve(proposal().id, 1, now) as ProposalApprovalResult.Approved).inbox
        var writes = 0
        val result = receiveRelayProposal(approved, message(), now) { writes++; true }
        assertEquals(1, writes)
        assertEquals(RelayDeliveryStatus.STORED, result.acknowledgement)
        assertFalse(result.newProposal)
        assertEquals(approved, result.inbox)
        assertNull(receiveRelayProposal(approved, message(), now) { false }.acknowledgement)
    }

    @Test fun revisedProposalRequiresFreshReviewButDoesNotOverwriteOriginal() {
        val initial = inbox()
        val revised = proposal(revision = 2).copy(stayMinutes = 10)
        val result = receiveRelayProposal(initial, message(revised), now) { true }
        assertTrue(result.newProposal)
        assertEquals(2, result.inbox.entries.single().proposal.revision)
        assertEquals(ProposalReviewStatus.PENDING, result.inbox.entries.single().status)
        assertEquals(1, initial.entries.single().proposal.revision)
    }

    @Test fun malformedExpiredAndConflictingMessagesNeverWriteTheInbox() {
        val initial = inbox()
        val cases = listOf(
            RelayMessage(messageId, "modu-home://proposal?v=99"),
            message(proposal().copy(expiresAtMillis = now + 1, deadlineAtMillis = null)),
            message(proposal().copy(stayMinutes = 15)),
        )
        cases.forEach {
            val result = receiveRelayProposal(initial, it, now + 2) { fail("Unexpected write"); false }
            assertEquals(RelayDeliveryStatus.REJECTED, result.acknowledgement)
            assertSame(initial, result.inbox)
        }
    }

    @Test fun fullUnreadInboxNeverDropsAProposalOrAcknowledgesTheNewOne() {
        val initial = JourneyProposalInbox.restore((0 until 20).map { ProposalInboxEntry(proposal("proposal-$it")) })
        val result = receiveRelayProposal(initial, message(proposal("new-proposal")), now) { fail("Unexpected write"); false }
        assertNull(result.acknowledgement)
        assertSame(initial, result.inbox)
        assertFalse(result.newProposal)
    }

    @Test fun reviewingOneEntryAllowsDeliveryWithoutDiscardingUnreadProposals() {
        val full = JourneyProposalInbox.restore((0 until 20).map { ProposalInboxEntry(proposal("proposal-$it")) })
        val initial = full.decline("proposal-0", 1)
        val result = receiveRelayProposal(initial, message(proposal("new-proposal")), now) { true }
        assertEquals(RelayDeliveryStatus.STORED, result.acknowledgement)
        assertEquals(20, result.inbox.entries.size)
        assertFalse(result.inbox.entries.any { it.proposal.id == "proposal-0" })
        assertTrue((1 until 20).all { number -> result.inbox.entries.any { it.proposal.id == "proposal-$number" } })
        assertTrue(result.inbox.entries.all { it.status == ProposalReviewStatus.PENDING })
    }

    @Test fun invalidClockLeavesMessageQueuedAndDoesNotTouchStorage() {
        val result = receiveRelayProposal(inbox(), message(), -1) { fail("Unexpected write"); false }
        assertNull(result.acknowledgement)
        assertFalse(result.newProposal)
    }
}
