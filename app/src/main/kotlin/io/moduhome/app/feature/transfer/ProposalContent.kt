package io.moduhome.app.feature.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposalInbox
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.RelayDeliveryStatus
import io.moduhome.app.core.transfer.ProposalReviewStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ProposalUiState(
    val inbox: ErrandProposalInbox,
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
    val exportedProposal: ErrandProposal? = null,
    val canSendToVehicle: Boolean = false,
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
)

@Composable
internal fun ProposalContent(state: ProposalUiState, actions: ProposalActions) {
    Dialog(onDismissRequest = actions.onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.92f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("함께 정하는 볼일", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    TextButton(onClick = actions.onDismiss) { Text("닫기") }
                }
                Text("받은 장소와 시간을 확인하고 이번 계획에 추가하세요")
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
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
                HorizontalDivider()
                val pending = state.inbox.entries.filter { it.status == ProposalReviewStatus.PENDING }
                if (!state.canApply) Text("진행 중인 계획은 유지돼요. 입력 화면으로 돌아온 뒤 제안을 추가할 수 있어요",
                    style = MaterialTheme.typography.bodyMedium)
                pending.forEach { entry ->
                    val proposal = entry.proposal
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (proposal.kind == VisitKind.PICKUP) "픽업" else "장보기"} · ${proposal.place.name}", fontWeight = FontWeight.Bold)
                            if (proposal.place.address.isNotBlank()) Text(proposal.place.address)
                            Text("체류 ${proposal.stayMinutes}분 · 편도 도보 ${proposal.walkingMinutesOneWay}분")
                            if (proposal.kind == VisitKind.PICKUP) Text(if (proposal.ready) "수령 준비 확인됨" else "수령 준비 확인 필요")
                            proposal.deadlineAtMillis?.let { deadline ->
                                Text("완료 마감 · " + DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault())))
                            }
                            Text("추가하면 같은 종류의 기존 볼일을 바꿔요", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { actions.onApprove(proposal.id, proposal.revision) }, enabled = state.canApply) { Text("이번 계획에 추가") }
                                TextButton(onClick = { actions.onDecline(proposal.id, proposal.revision) }) { Text("넘기기") }
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
                HorizontalDivider()
                Text("내 볼일 보내기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("선택한 장소와 시간을 QR이나 링크로 전달해요. 제안은 하루 동안 유효해요", style = MaterialTheme.typography.bodyMedium)
                if (state.pickupAvailable || state.groceriesAvailable) {
                    OutlinedButton(onClick = actions.onDismiss, enabled = !state.relayBusy) { Text("입력 화면에서 장소·시간 조건 편집") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.onExport(VisitKind.PICKUP) }, enabled = state.pickupAvailable && !state.relayBusy) { Text("픽업 제안 만들기") }
                    OutlinedButton(onClick = { actions.onExport(VisitKind.GROCERIES) }, enabled = state.groceriesAvailable && !state.relayBusy) { Text("장보기 제안 만들기") }
                }
                if (!state.pickupAvailable && !state.groceriesAvailable) Text("차량에서 선택한 장소를 먼저 받아보세요. 휴대폰에서 준비 여부와 시간을 바꿔 다시 보낼 수 있어요",
                    style = MaterialTheme.typography.bodySmall)
                if (state.qrBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.exportLink?.let {
                    state.exportedProposal?.let { proposal ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("보낼 조건 · ${if (proposal.kind == VisitKind.PICKUP) "픽업" else "장보기"}", fontWeight = FontWeight.Bold)
                                Text(proposal.place.name, fontWeight = FontWeight.SemiBold)
                                if (proposal.place.address.isNotBlank()) Text(proposal.place.address)
                                Text("체류 ${proposal.stayMinutes}분 · 편도 도보 ${proposal.walkingMinutesOneWay}분")
                                if (proposal.kind == VisitKind.PICKUP) Text(if (proposal.ready) "제안에 수령 준비됨으로 표시" else "수령 준비 확인 필요")
                                Text("완료 마감 · " + (proposal.deadlineAtMillis?.let { deadline ->
                                    DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault()))
                                } ?: "마감 없음"))
                            }
                        }
                    } ?: Text(state.exportName.orEmpty(), fontWeight = FontWeight.Bold)
                    Button(onClick = actions.onSendToVehicle, enabled = state.canSendToVehicle,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(when {
                            state.relayBusy -> "전송 확인 중"
                            state.delivery == RelayDeliveryStatus.QUEUED -> "차량 수신함 보관 대기"
                            state.delivery == RelayDeliveryStatus.STORED -> "차량 수신함에 보관됨"
                            state.delivery == RelayDeliveryStatus.REJECTED -> "조건 확인 후 새 제안을 만들어주세요"
                            else -> "연결된 차량으로 보내기"
                        })
                    }
                    state.qr?.let { qr -> Image(qr, "볼일 제안 QR 코드", Modifier.size(280.dp)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.onCopy) { Text("링크 복사") }
                        OutlinedButton(onClick = actions.onShare) { Text("공유") }
                    }
                    Text("링크에 장소와 좌표가 포함돼요. 함께 이동할 사람에게 전달하세요", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
