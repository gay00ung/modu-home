package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.JourneyProposal
import io.moduhome.app.core.transfer.DropoffProposal
import io.moduhome.app.feature.transfer.proposalLabel
import io.moduhome.app.core.transfer.ProposalReviewStatus
import io.moduhome.app.feature.home.visitLabel
import io.moduhome.app.feature.transfer.ProposalActions
import io.moduhome.app.feature.transfer.ProposalUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun VehicleProposalDialog(
    state: ProposalUiState,
    actions: ProposalActions,
    modifier: Modifier = Modifier,
) {
    val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.88f
    val pending = state.inbox.entries.filter { it.status == ProposalReviewStatus.PENDING }
    Dialog(onDismissRequest = actions.onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.widthIn(max = 1200.dp).fillMaxWidth(0.86f).heightIn(max = maximumHeight),
            color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("차량에서 받은 제안", fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    state.message?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(message, fontSize = 28.sp, lineHeight = 38.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (!state.canApply) {
                        Text("입력 화면으로 돌아온 뒤 이번 계획에 추가할 수 있어요", fontSize = 28.sp, lineHeight = 38.sp)
                    }
                    if (pending.isEmpty()) {
                        Text("지금 검토할 제안이 없어요", fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
                    } else {
                        pending.forEach { entry ->
                            VehicleProposalCard(entry.proposal, state.canApply,
                                onApprove = { if (entry.proposal is DropoffProposal) actions.onReviewDropoff(entry.proposal.id, entry.proposal.revision) else actions.onApprove(entry.proposal.id, entry.proposal.revision) },
                                onDecline = { actions.onDecline(entry.proposal.id, entry.proposal.revision) })
                        }
                    }
                    HorizontalDivider()
                    Text("내 볼일 QR", fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
                    Text("선택한 장소와 조건을 QR로 표시해요", fontSize = 28.sp, lineHeight = 38.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        OutlinedButton(onClick = { actions.onExport(VisitKind.PICKUP) },
                            enabled = state.pickupAvailable && !state.qrBusy,
                            modifier = Modifier.weight(1f).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                            Text("픽업 QR", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(onClick = { actions.onExport(VisitKind.GROCERIES) },
                            enabled = state.groceriesAvailable && !state.qrBusy,
                            modifier = Modifier.weight(1f).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                            Text("장보기 QR", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (!state.pickupAvailable && !state.groceriesAvailable) {
                        Text("픽업이나 장보기 장소를 먼저 선택해주세요", fontSize = 28.sp, lineHeight = 38.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.qrBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.exportLink != null) {
                        state.exportName?.takeIf { it.isNotBlank() }?.let { name ->
                            Text(name, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        state.qr?.let { qr ->
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                                    Image(bitmap = qr, contentDescription = "선택한 볼일 제안 QR 코드",
                                        modifier = Modifier.padding(16.dp).size(320.dp))
                                }
                                Text("장소와 좌표가 포함돼요 · 함께 이동할 사람에게만 보여주세요",
                                    fontSize = 28.sp, lineHeight = 38.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = actions.onDismiss,
                        modifier = Modifier.widthIn(min = 240.dp).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("닫기", fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleProposalCard(proposal: JourneyProposal, canApply: Boolean, onApprove: () -> Unit, onDecline: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(proposalLabel(proposal), fontSize = 28.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(proposal.place.name, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (proposal.place.address.isNotBlank() && proposal.place.address != proposal.place.name) {
                Text(proposal.place.address, fontSize = 28.sp, lineHeight = 38.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (proposal is ErrandProposal) {
                Text("체류 ${proposal.stayMinutes}분 · 편도 도보 ${proposal.walkingMinutesOneWay}분",
                    fontSize = 28.sp, lineHeight = 38.sp)
                if (proposal.kind == VisitKind.PICKUP) {
                    Text(if (proposal.ready) "수령 준비 · 준비 완료로 받았어요" else "수령 준비 · 확인이 필요해요",
                        fontSize = 28.sp, lineHeight = 38.sp)
                }
            }
            proposal.deadlineAtMillis?.let { deadline ->
                val text = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
                    .format(Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault()))
                Text("${if (proposal is DropoffProposal) "도착 희망" else "완료 마감"} · $text", fontSize = 28.sp, lineHeight = 38.sp)
            }
            Text(when (proposal) {
                is DropoffProposal -> "함께 귀가에서 동승자를 선택하고 하차지를 확인해요"
                is ErrandProposal -> "추가하면 기존 ${visitLabel(proposal.kind)} 장소와 조건을 바꿔요"
            }, fontSize = 28.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f).heightIn(min = 88.dp),
                    shape = RoundedCornerShape(18.dp)) { Text("넘기기", fontSize = 28.sp) }
                Button(onClick = { if (canApply) onApprove() }, enabled = canApply,
                    modifier = Modifier.weight(1.4f).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                    Text(if (proposal is DropoffProposal) "함께 귀가에서 검토" else "이번 계획에 추가", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
