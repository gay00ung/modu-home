package io.moduhome.app.feature.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.ErrandProposalInbox
import io.moduhome.app.core.transfer.ProposalReviewStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Selection, validation and persistence belong to the caller; this dialog only reviews one proposal. */
@Composable
internal fun SharedProposalDialog(
    inbox: ErrandProposalInbox,
    selectedProposal: ErrandProposal?,
    review: SharedProposalReview?,
    message: String?,
    canApply: Boolean,
    onSelect: (String, Int) -> Unit,
    onApprove: () -> Unit,
    onDecline: (String, Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.90f
    val pending = inbox.entries.filter { it.status == ProposalReviewStatus.PENDING }.map { it.proposal }
    val selectedIndex = pending.indexOfFirst { it == selectedProposal }
    val currentReview = review?.takeIf { selectedIndex >= 0 && it.proposal == selectedProposal }
    val existing = currentReview?.session?.draft?.errand
    val zoneId = currentReview?.zoneId ?: ZoneId.systemDefault()
    val received = selectedProposal?.let { proposal ->
        SharedErrandDraft(
            kind = proposal.kind,
            place = proposal.place,
            stayMinutes = proposal.stayMinutes,
            walkingMinutesOneWay = proposal.walkingMinutesOneWay,
            ready = proposal.ready,
            deadlineAtMillis = proposal.deadlineAtMillis,
        )
    }
    val approveEnabled = canApply && currentReview != null && selectedProposal != null
    val previousProposal = pending.getOrNull(selectedIndex - 1).takeIf { selectedIndex > 0 }
    val nextProposal = pending.getOrNull(if (selectedIndex < 0) 0 else selectedIndex + 1)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.widthIn(max = 1480.dp).fillMaxWidth(0.94f).heightIn(max = maximumHeight),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SharedProposalHeader(
                    showPager = pending.size > 1 || (pending.isNotEmpty() && selectedIndex < 0),
                    position = if (selectedIndex >= 0) selectedIndex + 1 else 0,
                    total = pending.size,
                    previous = previousProposal,
                    next = nextProposal,
                    onSelect = onSelect,
                )
                message?.takeIf(String::isNotBlank)?.let { selectedMessage ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp)) {
                        Text(selectedMessage, Modifier.fillMaxWidth().padding(16.dp), fontSize = 28.sp, lineHeight = 40.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                key(selectedProposal?.id, selectedProposal?.revision) {
                    Column(
                        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when {
                            selectedProposal == null -> Text(
                                if (pending.isEmpty()) "아직 받은 볼일이 없어요" else "다음을 눌러 받은 볼일을 확인해주세요",
                                fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium,
                            )
                            received != null -> {
                                Text("받은 제안의 조건이에요", fontSize = 28.sp, lineHeight = 40.sp,
                                    color = MaterialTheme.colorScheme.primary)
                                if (existing?.deadlineAtMillis != null && received.deadlineAtMillis == null) {
                                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
                                        Text("기존 완료 마감 ${sharedProposalDeadline(existing.deadlineAtMillis, zoneId)}이 없어져요",
                                            Modifier.fillMaxWidth().padding(16.dp), fontSize = 28.sp, lineHeight = 40.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    if (maxWidth >= 1000.dp) {
                                        SharedProposalComparison(existing, received, zoneId)
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            if (existing != null) {
                                                SharedProposalCompactConditions("기존 볼일", existing, zoneId)
                                            }
                                            SharedProposalCompactConditions("새 볼일", received, zoneId)
                                        }
                                    }
                                }
                                if (received.kind == VisitKind.PICKUP || existing?.kind == VisitKind.PICKUP) {
                                    Text("수령 준비는 제안이나 계획에 저장된 상태이며 매장 확인 정보는 아니에요",
                                        fontSize = 28.sp, lineHeight = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("반영 후 경로를 다시 비교해요. 현재 내비 안내는 바꾸지 않아요",
                    fontSize = 28.sp, lineHeight = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SharedProposalFooter(
                    approveLabel = if (existing != null) "이 볼일로 바꾸기" else "계획에 추가",
                    approveEnabled = approveEnabled,
                    declineEnabled = selectedIndex >= 0,
                    onApprove = { if (approveEnabled) onApprove() },
                    onDecline = { if (selectedIndex >= 0) selectedProposal?.let { onDecline(it.id, it.revision) } },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun SharedProposalHeader(
    showPager: Boolean,
    position: Int,
    total: Int,
    previous: ErrandProposal?,
    next: ErrandProposal?,
    onSelect: (String, Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (showPager && maxWidth >= 900.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("받은 볼일", Modifier.weight(1f), fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                SharedProposalPager(position, total, previous, next, onSelect, Modifier.width(420.dp))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("받은 볼일", fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                if (showPager) SharedProposalPager(position, total, previous, next, onSelect, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SharedProposalPager(
    position: Int,
    total: Int,
    previous: ErrandProposal?,
    next: ErrandProposal?,
    onSelect: (String, Int) -> Unit,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(
            onClick = { previous?.let { onSelect(it.id, it.revision) } }, enabled = previous != null,
            modifier = Modifier.weight(1f).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp),
        ) { Text("이전", fontSize = 28.sp) }
        Text("$position / $total", Modifier.widthIn(min = 88.dp), fontSize = 28.sp, lineHeight = 38.sp,
            textAlign = TextAlign.Center)
        OutlinedButton(
            onClick = { next?.let { onSelect(it.id, it.revision) } }, enabled = next != null,
            modifier = Modifier.weight(1f).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp),
        ) { Text("다음", fontSize = 28.sp) }
    }
}

@Composable
private fun SharedProposalComparison(existing: SharedErrandDraft?, received: SharedErrandDraft, zoneId: ZoneId) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.width(140.dp))
                if (existing != null) Text("기존 볼일", Modifier.weight(1f), fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
                Text("새 볼일", Modifier.weight(1f), fontSize = 30.sp, lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            SharedProposalComparisonRow("종류", existing?.let(::sharedProposalKind), sharedProposalKind(received))
            SharedProposalComparisonRow("장소·주소", existing?.let(::sharedProposalPlace), sharedProposalPlace(received))
            SharedProposalComparisonRow("체류 시간", existing?.let { "${it.stayMinutes}분" }, "${received.stayMinutes}분")
            SharedProposalComparisonRow("편도 도보", existing?.let { "${it.walkingMinutesOneWay}분" }, "${received.walkingMinutesOneWay}분")
            SharedProposalComparisonRow("수령 준비", existing?.let(::sharedProposalReady), sharedProposalReady(received))
            SharedProposalComparisonRow("완료 마감", existing?.let { sharedProposalDeadline(it.deadlineAtMillis, zoneId) },
                sharedProposalDeadline(received.deadlineAtMillis, zoneId))
        }
    }
}

@Composable
private fun SharedProposalComparisonRow(label: String, oldValue: String?, newValue: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(140.dp), fontSize = 28.sp, lineHeight = 40.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (oldValue != null) Text(oldValue, Modifier.weight(1f), fontSize = 32.sp, lineHeight = 44.sp)
        Text(newValue, Modifier.weight(1f), fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SharedProposalCompactConditions(title: String, condition: SharedErrandDraft, zoneId: ZoneId) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
            SharedProposalComparisonRow("종류", null, sharedProposalKind(condition))
            SharedProposalComparisonRow("장소·주소", null, sharedProposalPlace(condition))
            SharedProposalComparisonRow("체류 시간", null, "${condition.stayMinutes}분")
            SharedProposalComparisonRow("편도 도보", null, "${condition.walkingMinutesOneWay}분")
            SharedProposalComparisonRow("수령 준비", null, sharedProposalReady(condition))
            SharedProposalComparisonRow("완료 마감", null, sharedProposalDeadline(condition.deadlineAtMillis, zoneId))
        }
    }
}

private fun sharedProposalKind(condition: SharedErrandDraft): String = if (condition.kind == VisitKind.PICKUP) "픽업" else "장보기"

private fun sharedProposalPlace(condition: SharedErrandDraft): String =
    "${condition.place?.name ?: "장소 선택 전"}\n${condition.place?.address?.takeIf(String::isNotBlank) ?: "등록된 주소 없음"}"

private fun sharedProposalReady(condition: SharedErrandDraft): String = when {
    condition.kind != VisitKind.PICKUP -> "적용 없음"
    condition.ready -> "준비됨으로 표시"
    else -> "확인 필요"
}

@Composable
private fun SharedProposalFooter(
    approveLabel: String,
    approveEnabled: Boolean,
    declineEnabled: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SharedProposalSecondaryAction("넘기기", declineEnabled, onDecline, Modifier.weight(1f))
                SharedProposalSecondaryAction("닫기", true, onDismiss, Modifier.weight(1f))
                SharedProposalApproveAction(approveLabel, approveEnabled, onApprove, Modifier.weight(1.6f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SharedProposalSecondaryAction("넘기기", declineEnabled, onDecline, Modifier.weight(1f))
                    SharedProposalSecondaryAction("닫기", true, onDismiss, Modifier.weight(1f))
                }
                SharedProposalApproveAction(approveLabel, approveEnabled, onApprove, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SharedProposalSecondaryAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 88.dp),
        shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(label, fontSize = 28.sp, lineHeight = 40.sp)
    }
}

@Composable
private fun SharedProposalApproveAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 88.dp),
        shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(label, fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    }
}

private fun sharedProposalDeadline(atMillis: Long?, zoneId: ZoneId): String = atMillis?.let { at ->
    runCatching {
        DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREAN).format(Instant.ofEpochMilli(at).atZone(zoneId))
    }.getOrElse { "마감 시각 확인 필요" }
} ?: "마감 없음"
