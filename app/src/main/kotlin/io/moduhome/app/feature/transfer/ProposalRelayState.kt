package io.moduhome.app.feature.transfer

import androidx.compose.ui.graphics.ImageBitmap
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.RelayDeliveryStatus
import io.moduhome.app.core.transfer.RelayFailure
import io.moduhome.app.core.transfer.RelayMessage
import io.moduhome.app.core.transfer.RelayReceiverSession
import io.moduhome.app.core.transfer.RelayResult
import io.moduhome.app.core.transfer.RelayRoomWelcome
import io.moduhome.app.core.transfer.RelaySenderSession
import io.moduhome.app.core.vehicle.DrivingInteraction
import java.util.UUID

internal data class RelaySeedChoice(
    val kind: VisitKind,
    val place: NavigationPoint,
    val stayMinutes: Int,
    val walkingMinutesOneWay: Int,
    val ready: Boolean,
    val deadlineAtMillis: Long?,
) {
    fun proposal(nowMillis: Long): ErrandProposal? = runCatching {
        ErrandProposal(UUID.randomUUID().toString(), 1, nowMillis,
            nowMillis + ErrandProposal.MAX_LIFETIME_MILLIS, kind, place,
            stayMinutes, walkingMinutesOneWay, ready, deadlineAtMillis)
            .takeIf { it.unavailableReason(nowMillis) == null }
    }.getOrNull()
    override fun toString(): String = "RelaySeedChoice(kind=$kind, conditions=redacted)"
}

internal enum class RelayManualAction { OPEN, JOIN, SEND, CLOSE }

/** Owned by ModuHomeApp with remember only. Pairing credentials never enter saved state. */
internal data class ProposalRelayState(
    val receiver: RelayReceiverSession? = null,
    val sender: RelaySenderSession? = null,
    val candidate: RelaySenderSession? = null,
    val vehicleDialogOpen: Boolean = false,
    val seedChoices: List<RelaySeedChoice> = emptyList(),
    val selectedSeedKind: VisitKind? = null,
    val reviewedInteraction: DrivingInteraction? = null,
    val operationInteraction: DrivingInteraction? = null,
    val action: RelayManualAction? = null,
    val generation: Long = 0,
    val qr: ImageBitmap? = null,
    val outgoing: RelayMessage? = null,
    val delivery: RelayDeliveryStatus? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = action != null

    /** Queued delivery already has its own observer; manual actions own their result. */
    fun canCheckSender(target: RelaySenderSession, nowMillis: Long): Boolean =
        sender === target && !busy && candidate == null && delivery != RelayDeliveryStatus.QUEUED &&
            nowMillis in 0 until target.expiresAtMillis

    fun afterSenderCheck(
        target: RelaySenderSession,
        observedGeneration: Long,
        nowMillis: Long,
        result: RelayResult<RelayRoomWelcome>,
    ): ProposalRelayState? {
        if (generation != observedGeneration || !canCheckSender(target, nowMillis)) return null
        return when (result) {
            is RelayResult.Success -> null // A liveness read never imports the shared seed again.
            is RelayResult.Failure -> when (result.code) {
                RelayFailure.CLOSED, RelayFailure.UNAUTHORIZED -> copy(
                    sender = null, outgoing = null, delivery = null, generation = generation + 1,
                    message = if (delivery == RelayDeliveryStatus.STORED)
                        "차량 연결이 종료됐어요. 이미 보관한 제안은 차량에 남아 있어요"
                    else "차량 연결이 종료됐어요. 새 QR로 다시 연결해주세요. 만든 제안은 그대로 남아 있어요",
                )
                RelayFailure.CONFIGURATION, RelayFailure.CONNECTION, RelayFailure.FULL,
                RelayFailure.CONFLICT, RelayFailure.INVALID_RESPONSE -> null
            }
        }
    }

    override fun toString(): String = "ProposalRelayState(receiver=${receiver != null}, sender=${sender != null}, action=$action, delivery=$delivery)"
}

/** Keep an independently valid review visible when only an older connection expires. */
internal fun ProposalRelayState.expire(nowMillis: Long): ProposalRelayState? {
    val receiverExpired = receiver?.let { nowMillis < 0 || nowMillis >= it.expiresAtMillis } == true
    val senderExpired = sender?.let { nowMillis < 0 || nowMillis >= it.expiresAtMillis } == true
    val candidateExpired = candidate?.let { nowMillis < 0 || nowMillis >= it.expiresAtMillis } == true
    if (!receiverExpired && !senderExpired && !candidateExpired) return null
    val nextCandidate = candidate.takeUnless { candidateExpired }
    val keepVehicleDialog = vehicleDialogOpen && nowMillis >= 0
    return copy(
        receiver = receiver.takeUnless { receiverExpired },
        qr = qr.takeUnless { receiverExpired },
        sender = sender.takeUnless { senderExpired },
        candidate = nextCandidate,
        outgoing = outgoing.takeUnless { senderExpired },
        delivery = delivery.takeUnless { senderExpired },
        vehicleDialogOpen = keepVehicleDialog,
        reviewedInteraction = reviewedInteraction.takeIf { keepVehicleDialog || nextCandidate != null },
        action = null,
        operationInteraction = null,
        generation = generation + 1,
        message = if (nowMillis < 0) "기기 시각을 확인한 뒤 다시 연결해주세요"
            else "연결 유효시간이 지났어요. 이미 받은 제안은 보관돼요",
    )
}

internal data class ProposalRelayActions(
    val openVehicle: () -> Unit,
    val chooseSeed: (VisitKind?) -> Unit,
    val createRoom: () -> Unit,
    val closeRoom: () -> Unit,
    val dismissVehicle: () -> Unit,
    val acceptJoinLink: (String) -> Boolean,
    val joinVehicle: () -> Unit,
    val dismissJoin: () -> Unit,
    val disconnectSender: () -> Unit,
    val send: (String) -> Unit,
)
