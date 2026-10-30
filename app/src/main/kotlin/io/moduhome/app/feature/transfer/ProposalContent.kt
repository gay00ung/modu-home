package io.moduhome.app.feature.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.JourneyProposalInbox
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.JourneyProposal
import io.moduhome.app.core.transfer.DropoffProposal
import io.moduhome.app.core.transfer.RelayDeliveryStatus
import io.moduhome.app.core.transfer.ProposalReviewStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ProposalUiState(
    val inbox: JourneyProposalInbox,
    val input: String = "",
    val message: String? = null,
    val canApply: Boolean = false,
    val exportLink: String? = null,
    val exportName: String? = null,
    val qr: ImageBitmap? = null,
    val qrBusy: Boolean = false,
    val pickupAvailable: Boolean = false,
    val groceriesAvailable: Boolean = false,
    val connectedVehicle: String? = null,
    val relayMessage: String? = null,
    val relayBusy: Boolean = false,
    val delivery: RelayDeliveryStatus? = null,
    val exportedProposal: JourneyProposal? = null,
    val canSendToVehicle: Boolean = false,
    val canEdit: Boolean = false,
    val canEditPrepared: Boolean = false,
)

internal data class ProposalActions(
    val onInput: (String) -> Unit,
    val onReceive: () -> Unit,
    val onPaste: () -> Unit,
    val onReadQr: () -> Unit,
    val onApprove: (String, Int) -> Unit,
    val onDecline: (String, Int) -> Unit,
    val onExport: (VisitKind) -> Unit,
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
    val onDismiss: () -> Unit,
    val onSendToVehicle: () -> Unit = {},
    val onDisconnectVehicle: () -> Unit = {},
    val onEdit: (String, Int) -> Unit = { _, _ -> },
    val onEditPrepared: () -> Unit = {},
    val onBackToProposals: () -> Unit = {},
    val onCreateFromAddress: () -> Unit = {},
    val onReviewDropoff: (String, Int) -> Unit = { _, _ -> },
)

@Composable
internal fun ProposalContent(state: ProposalUiState, actions: ProposalActions, modifier: Modifier = Modifier) {
    Dialog(onDismissRequest = actions.onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.widthIn(max = 720.dp).fillMaxWidth(0.92f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.extraLarge) {
            val prepared = state.exportedProposal
            if (prepared != null && state.exportLink != null) {
                PreparedProposalContent(state, prepared, actions)
            } else {
                ProposalListContent(state, actions)
            }
        }
    }
}

@Composable
private fun ProposalListContent(state: ProposalUiState, actions: ProposalActions) {
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("함께 정하는 볼일", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = actions.onDismiss) { Text("닫기") }
        }
        Text("볼일의 조건을 정해 차량으로 보내세요")
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Button(onClick = actions.onCreateFromAddress, enabled = state.canEdit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("주소로 새 볼일", fontSize = 16.sp)
        }
        Text("연결된 차량", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (state.connectedVehicle != null) {
            Text(state.connectedVehicle, fontWeight = FontWeight.Bold)
            Text("보낸 제안은 차량 수신함에 보관되고 운전자가 확인해요")
            TextButton(onClick = actions.onDisconnectVehicle, enabled = !state.relayBusy) { Text("이 휴대폰 연결 해제") }
        } else {
            Text("차량의 휴대폰 연결 QR 링크를 열어주세요. QR 이미지를 골라 연결할 수도 있어요")
            OutlinedButton(onClick = actions.onReadQr, enabled = !state.qrBusy && !state.relayBusy) { Text("차량 연결 QR 이미지 열기") }
        }
        state.relayMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.qrBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        HorizontalDivider()
        val pending = state.inbox.entries.filter { it.status == ProposalReviewStatus.PENDING }
        Text("받은 볼일", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        pending.forEach { entry ->
            val proposal = entry.proposal
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${proposalLabel(proposal)} · ${proposal.place.name}", fontWeight = FontWeight.Bold)
                    if (proposal.place.address.isNotBlank() && proposal.place.address != proposal.place.name) Text(proposal.place.address)
                    if (proposal is ErrandProposal) {
                        Text("체류 ${proposal.stayMinutes}분 · 편도 도보 ${proposal.walkingMinutesOneWay}분")
                        if (proposal.kind == VisitKind.PICKUP) Text(if (proposal.ready) "제안에 수령 준비됨으로 표시" else "수령 준비 확인 필요")
                    }
                    proposal.deadlineAtMillis?.let { deadline ->
                        Text("완료 마감 · " + DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault())))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.onEdit(proposal.id, proposal.revision) }, enabled = state.canEdit,
                            modifier = Modifier.heightIn(min = 56.dp)) { Text("조건 정해 보내기") }
                        TextButton(onClick = { actions.onDecline(proposal.id, proposal.revision) },
                            modifier = Modifier.heightIn(min = 56.dp)) { Text("넘기기") }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("제안 받기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = state.input, onValueChange = actions.onInput, label = { Text("제안 링크") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onReceive, enabled = state.input.isNotBlank()) { Text("내용 확인") }
            OutlinedButton(onClick = actions.onPaste) { Text("붙여넣기") }
            OutlinedButton(onClick = actions.onReadQr, enabled = !state.qrBusy) { Text("QR 이미지 열기") }
        }
        if (state.exportLink != null) {
            Text(state.exportName.orEmpty(), fontWeight = FontWeight.SemiBold)
            ProposalQrAndShare(state, actions)
        }
    }
}

@Composable
private fun PreparedProposalContent(state: ProposalUiState, proposal: JourneyProposal, actions: ProposalActions) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (proposal is DropoffProposal) "보낼 하차지 확인" else "보낼 볼일 확인", modifier = Modifier.weight(1f), fontSize = 24.sp, lineHeight = 32.sp,
                fontWeight = FontWeight.Bold)
            TextButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("닫기", fontSize = 16.sp)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(proposalLabel(proposal),
                    fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(proposal.place.name, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                if (proposal.place.address.isNotBlank() && proposal.place.address != proposal.place.name) Text(proposal.place.address, fontSize = 16.sp, lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (proposal is ErrandProposal) {
                    Text("체류 ${proposal.stayMinutes}분 · 편도 도보 ${proposal.walkingMinutesOneWay}분", fontSize = 16.sp, lineHeight = 24.sp)
                    if (proposal.kind == VisitKind.PICKUP) Text(if (proposal.ready) "제안에 수령 준비됨으로 표시" else "수령 준비 확인 필요",
                        fontSize = 16.sp, lineHeight = 24.sp)
                }
                Text((if (proposal is DropoffProposal) "도착 희망 · " else "완료 마감 · ") + (proposal.deadlineAtMillis?.let { deadline ->
                    DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault()))
                } ?: "시간 제한 없음"), fontSize = 16.sp, lineHeight = 24.sp)
            }
            HorizontalDivider()
            if (state.connectedVehicle != null) {
                Text("차량 연결 · ${state.connectedVehicle}", fontSize = 16.sp, lineHeight = 24.sp)
            } else {
                Text("연결된 차량이 없어요", fontSize = 16.sp, lineHeight = 24.sp)
                OutlinedButton(onClick = actions.onReadQr, enabled = !state.qrBusy && !state.relayBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("차량 연결 QR 이미지 열기", fontSize = 16.sp)
                }
            }
            state.message?.let { Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.primary) }
            state.relayMessage?.let { Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.primary) }
            ProposalQrAndShare(state, actions)
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onSendToVehicle,
                enabled = state.canSendToVehicle && state.connectedVehicle != null && !state.relayBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(when {
                    state.relayBusy -> "전송 확인 중"
                    state.delivery == RelayDeliveryStatus.QUEUED -> "차량 수신함 보관 대기"
                    state.delivery == RelayDeliveryStatus.STORED -> "차량 수신함에 보관됨"
                    state.delivery == RelayDeliveryStatus.REJECTED -> "조건 확인 후 새 제안을 만들어주세요"
                    state.connectedVehicle == null -> "차량 미연결 · 연결 후 보내기"
                    else -> "차량으로 보내기"
                }, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canEditPrepared) OutlinedButton(onClick = actions.onEditPrepared, enabled = !state.relayBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text("조건 수정", fontSize = 16.sp)
                }
                TextButton(onClick = actions.onBackToProposals, enabled = !state.relayBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text("다른 제안", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun ProposalQrAndShare(state: ProposalUiState, actions: ProposalActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.qrBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.qr?.let { qr -> Image(qr, "볼일 제안 QR 코드", Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onCopy, enabled = state.exportLink != null,
                modifier = Modifier.heightIn(min = 56.dp)) { Text("링크 복사", fontSize = 16.sp) }
            OutlinedButton(onClick = actions.onShare, enabled = state.exportLink != null,
                modifier = Modifier.heightIn(min = 56.dp)) { Text("공유", fontSize = 16.sp) }
        }
        Text("링크에 장소와 좌표가 포함돼요. 함께 이동할 사람에게 전달하세요", fontSize = 16.sp, lineHeight = 24.sp)
    }
}

internal fun proposalLabel(proposal: JourneyProposal): String = when (proposal) {
    is ErrandProposal -> if (proposal.kind == VisitKind.PICKUP) "픽업" else "장보기"
    is DropoffProposal -> "하차지 · ${proposal.passengerName}"
}
