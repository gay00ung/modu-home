package io.moduhome.app.data.transfer

import io.moduhome.app.core.transfer.ErrandProposalInbox
import io.moduhome.app.core.transfer.ProposalReceiveFailure
import io.moduhome.app.core.transfer.ProposalReceiveResult
import io.moduhome.app.core.transfer.RelayDeliveryStatus
import io.moduhome.app.core.transfer.RelayMessage

internal data class RelayInboxReceipt(
    val inbox: ErrandProposalInbox,
    val acknowledgement: RelayDeliveryStatus?,
    val newProposal: Boolean = false,
    val reason: String? = null,
)

/** A server receipt only follows a durable local write, including a redelivered duplicate. */
internal fun receiveRelayProposal(
    inbox: ErrandProposalInbox,
    message: RelayMessage,
    nowMillis: Long,
    save: (ErrandProposalInbox) -> Boolean,
): RelayInboxReceipt {
    if (nowMillis < 0) return RelayInboxReceipt(inbox, null, reason = "기기 시각을 확인해주세요")
    val proposal = when (val decoded = ProposalLinkCodec.decode(message.link, nowMillis)) {
        is ProposalDecodeResult.Invalid -> return RelayInboxReceipt(inbox, RelayDeliveryStatus.REJECTED, reason = decoded.reason)
        is ProposalDecodeResult.Valid -> decoded.proposal
    }
    return when (val received = inbox.receive(proposal, nowMillis)) {
        is ProposalReceiveResult.Pending -> if (save(received.inbox)) {
            RelayInboxReceipt(received.inbox, RelayDeliveryStatus.STORED, newProposal = true)
        } else RelayInboxReceipt(inbox, null, reason = "받은 볼일을 저장하지 못했어요. 연결된 동안 다시 받을게요")
        ProposalReceiveResult.Duplicate -> if (save(inbox)) {
            RelayInboxReceipt(inbox, RelayDeliveryStatus.STORED)
        } else RelayInboxReceipt(inbox, null, reason = "받은 볼일의 저장을 확인하지 못했어요. 다시 시도할게요")
        is ProposalReceiveResult.Rejected -> RelayInboxReceipt(
            inbox,
            if (received.failure == ProposalReceiveFailure.FULL) null else RelayDeliveryStatus.REJECTED,
            reason = received.reason,
        )
    }
}
