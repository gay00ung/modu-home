package io.moduhome.app.feature.transfer

import io.moduhome.app.core.transfer.RelayDeliveryStatus
import io.moduhome.app.core.transfer.RelayMessage
import io.moduhome.app.core.transfer.RelayReceiverSession
import io.moduhome.app.core.transfer.RelaySenderSession
import io.moduhome.app.core.vehicle.DrivingInteraction
import org.junit.Assert.*
import org.junit.Test

class ProposalRelayStateTest {
    private val parked = DrivingInteraction.Known(distractionOptimizationRequired = false)

    @Test fun validConnectionsNeedNoExpiryTransition() {
        val state = ProposalRelayState(sender = sender(2_000), reviewedInteraction = parked)
        assertNull(state.expire(1_999))
        assertNull(ProposalRelayState().expire(1_000))
    }

    @Test fun oldSenderExpiryKeepsNewCandidateReviewConnectable() {
        val candidate = sender(3_000, "00000000-0000-0000-0000-000000000002")
        val outgoing = message()
        val state = ProposalRelayState(sender = sender(1_000), candidate = candidate,
            reviewedInteraction = parked, operationInteraction = parked, action = RelayManualAction.JOIN,
            outgoing = outgoing, delivery = RelayDeliveryStatus.QUEUED, generation = 9)

        val expired = requireNotNull(state.expire(1_000))

        assertNull(expired.sender)
        assertSame(candidate, expired.candidate)
        assertSame(parked, expired.reviewedInteraction)
        assertNull(expired.outgoing)
        assertNull(expired.delivery)
        assertNull(expired.action)
        assertNull(expired.operationInteraction)
        assertEquals(10L, expired.generation)
        assertFalse(expired.busy)
    }

    @Test fun receiverExpiryKeepsOpenVehicleReviewReadyForNewRoom() {
        val receiver = receiver(1_000)
        val state = ProposalRelayState(receiver = receiver, vehicleDialogOpen = true,
            reviewedInteraction = parked, action = RelayManualAction.CLOSE, operationInteraction = parked)

        val expired = requireNotNull(state.expire(1_000))

        assertNull(expired.receiver)
        assertNull(expired.qr)
        assertTrue(expired.vehicleDialogOpen)
        assertSame(parked, expired.reviewedInteraction)
        assertFalse(expired.busy)
        assertNull(expired.operationInteraction)
    }

    @Test fun candidateExpiryPreservesCurrentSenderAndItsDelivery() {
        val connected = sender(3_000)
        val outgoing = message()
        val state = ProposalRelayState(sender = connected,
            candidate = sender(1_000, "00000000-0000-0000-0000-000000000002"),
            reviewedInteraction = parked, outgoing = outgoing, delivery = RelayDeliveryStatus.QUEUED)

        val expired = requireNotNull(state.expire(1_000))

        assertSame(connected, expired.sender)
        assertNull(expired.candidate)
        assertNull(expired.reviewedInteraction)
        assertSame(outgoing, expired.outgoing)
        assertEquals(RelayDeliveryStatus.QUEUED, expired.delivery)
    }

    @Test fun expiredClosedVehicleDoesNotRetainAReviewMarker() {
        val state = ProposalRelayState(receiver = receiver(1_000), reviewedInteraction = parked)
        val expired = requireNotNull(state.expire(1_001))
        assertNull(expired.receiver)
        assertFalse(expired.vehicleDialogOpen)
        assertNull(expired.reviewedInteraction)
    }

    @Test fun sameExpiryClearsBothSenderAndCandidate() {
        val state = ProposalRelayState(sender = sender(1_000), candidate = sender(1_000,
            "00000000-0000-0000-0000-000000000002"), reviewedInteraction = parked)
        val expired = requireNotNull(state.expire(1_000))
        assertNull(expired.sender)
        assertNull(expired.candidate)
        assertNull(expired.reviewedInteraction)
    }

    @Test fun invalidClockClearsCredentialsAndClosesReviewSurfaces() {
        val state = ProposalRelayState(receiver = receiver(3_000), sender = sender(3_000),
            candidate = sender(4_000, "00000000-0000-0000-0000-000000000002"),
            vehicleDialogOpen = true, reviewedInteraction = parked, generation = 7)
        val expired = requireNotNull(state.expire(-1))
        assertNull(expired.receiver)
        assertNull(expired.sender)
        assertNull(expired.candidate)
        assertFalse(expired.vehicleDialogOpen)
        assertNull(expired.reviewedInteraction)
        assertEquals(8L, expired.generation)
    }

    private fun sender(expires: Long, id: String = "00000000-0000-0000-0000-000000000001") =
        RelaySenderSession("http://10.0.2.2:48680", id, "a".repeat(43), expires)

    private fun receiver(expires: Long): RelayReceiverSession {
        val sender = sender(expires)
        return RelayReceiverSession(sender.baseUrl, sender.roomId, "b".repeat(43), sender)
    }

    private fun message() = RelayMessage("00000000-0000-0000-0000-000000000003", "modu-home://proposal?fixture=1")
}
