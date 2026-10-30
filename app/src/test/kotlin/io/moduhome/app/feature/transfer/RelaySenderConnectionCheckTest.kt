package io.moduhome.app.feature.transfer

import io.moduhome.app.core.transfer.*
import org.junit.Assert.*
import org.junit.Test

class RelaySenderConnectionCheckTest {
    private val sender = session()
    private val message = RelayMessage("00000000-0000-0000-0000-000000000003", "modu-home://proposal?fixture=1")
    private val stored = ProposalRelayState(sender = sender, outgoing = message,
        delivery = RelayDeliveryStatus.STORED, generation = 7)
    private val closed = RelayResult.Failure(RelayFailure.CLOSED, "closed")

    @Test fun closedRoomAfterStorageDropsConnectionButExplainsTheStoredProposalRemains() {
        val result = requireNotNull(stored.afterSenderCheck(sender, 7, 1_000, closed))
        assertNull(result.sender)
        assertNull(result.outgoing)
        assertNull(result.delivery)
        assertEquals(8L, result.generation)
        assertTrue(result.message.orEmpty().contains("이미 보관한 제안은 차량에 남아"))
    }

    @Test fun revokedAccessBeforeSendingDoesNotClaimAnythingWasStored() {
        val state = ProposalRelayState(sender = sender)
        val result = requireNotNull(state.afterSenderCheck(sender, 0, 1_000,
            RelayResult.Failure(RelayFailure.UNAUTHORIZED, "revoked")))
        assertNull(result.sender)
        assertTrue(result.message.orEmpty().contains("새 QR"))
        assertFalse(result.message.orEmpty().contains("이미 보관한"))
    }

    @Test fun queuedDeliveryKeepsItsExistingReceiptObserver() {
        val state = stored.copy(delivery = RelayDeliveryStatus.QUEUED)
        assertFalse(state.canCheckSender(sender, 1_000))
        assertNull(state.afterSenderCheck(sender, 7, 1_000, closed))
        assertTrue(state.copy(delivery = RelayDeliveryStatus.STORED).canCheckSender(sender, 1_000))
    }

    @Test fun manualActionsAndNewQrReviewsRejectAnOlderCheck() {
        for (action in RelayManualAction.entries) {
            val state = stored.copy(action = action)
            assertFalse(state.canCheckSender(sender, 1_000))
            assertNull(state.afterSenderCheck(sender, 7, 1_000, closed))
        }
        val review = stored.copy(candidate = session("00000000-0000-0000-0000-000000000002"))
        assertFalse(review.canCheckSender(sender, 1_000))
        assertNull(review.afterSenderCheck(sender, 7, 1_000, closed))
    }

    @Test fun lateResultsCannotRemoveANewSessionOrANewGeneration() {
        val rejoined = stored.copy(sender = session())
        assertNull(rejoined.afterSenderCheck(sender, 7, 1_000, closed))
        assertNull(stored.copy(generation = 8).afterSenderCheck(sender, 7, 1_000, closed))
    }

    @Test fun validMetadataAndTransientFailuresDoNotReimportSeedsOrChangeDelivery() {
        val welcome = RelayRoomWelcome(sender.expiresAtMillis, "modu-home://proposal?fixture=changed")
        assertNull(stored.afterSenderCheck(sender, 7, 1_000, RelayResult.Success(welcome)))
        for (failure in listOf(RelayFailure.CONNECTION, RelayFailure.CONFIGURATION, RelayFailure.FULL,
            RelayFailure.CONFLICT, RelayFailure.INVALID_RESPONSE)) {
            assertNull(stored.afterSenderCheck(sender, 7, 1_000, RelayResult.Failure(failure, "temporary")))
        }
        assertSame(message, stored.outgoing)
        assertEquals(RelayDeliveryStatus.STORED, stored.delivery)
    }

    @Test fun expiredSessionsAndInvalidClocksStayWithTheExpiryOwner() {
        for (now in listOf(-1L, sender.expiresAtMillis, Long.MAX_VALUE)) {
            assertFalse(stored.canCheckSender(sender, now))
            assertNull(stored.afterSenderCheck(sender, 7, now, closed))
        }
    }

    private fun session(id: String = "00000000-0000-0000-0000-000000000001") =
        RelaySenderSession("https://relay.example", id, "a".repeat(43), 100_000)
}
