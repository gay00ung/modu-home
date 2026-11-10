package io.moduhome.app.feature.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.transfer.CompanionDeadlineChoice
import io.moduhome.app.core.transfer.CompanionDropoffEdit
import io.moduhome.app.core.transfer.CompanionDropoffDraft
import io.moduhome.app.core.transfer.CompanionDropoffField
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun CompanionDropoffEditor(
    draft: CompanionDropoffDraft,
    errors: Map<CompanionDropoffField, String>,
    editingAllowed: Boolean,
    onEdit: (CompanionDropoffEdit) -> Unit,
    onPrepare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Text("내 하차지 보내기", Modifier.padding(20.dp), fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(draft.place.name, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                if (draft.place.address != draft.place.name) Text(draft.place.address, fontSize = 18.sp, lineHeight = 26.sp)
                OutlinedTextField(draft.passengerName, { onEdit(CompanionDropoffEdit.Name(it.take(32))) },
                    label = { Text("이름 · 선택") }, singleLine = true, enabled = editingAllowed,
                    supportingText = { Text("운전자가 누구의 하차지인지 구분할 때 사용해요") },
                    isError = CompanionDropoffField.NAME in errors, modifier = Modifier.fillMaxWidth())
                Text("언제까지 도착하고 싶으세요?", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                Text("선택한 시간을 넘기는 순서는 출발할 수 없어요. 교통 상황에 따른 실제 도착을 보장하지는 않아요",
                    fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val choices = buildList {
                        if (draft.source != null) add(CompanionDeadlineChoice.KEEP_ORIGINAL to "받은 시간 유지")
                        add(CompanionDeadlineChoice.NONE to "시간 제한 없음")
                        add(CompanionDeadlineChoice.TODAY to "${draft.baseDate.monthValue}/${draft.baseDate.dayOfMonth}")
                        val tomorrow = draft.baseDate.plusDays(1)
                        add(CompanionDeadlineChoice.TOMORROW to "${tomorrow.monthValue}/${tomorrow.dayOfMonth}")
                    }
                    choices.forEach { (choice, label) ->
                        FilterChip(draft.deadline == choice, { onEdit(CompanionDropoffEdit.Deadline(choice)) },
                            enabled = editingAllowed, label = { Text(label, fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 56.dp))
                    }
                }
                if (draft.deadline == CompanionDeadlineChoice.TODAY || draft.deadline == CompanionDeadlineChoice.TOMORROW) {
                    OutlinedTextField(draft.deadlineTime, { onEdit(CompanionDropoffEdit.Time(it.take(5))) },
                        label = { Text("도착 희망 시각 · 21:30") }, singleLine = true, enabled = editingAllowed,
                        isError = CompanionDropoffField.ARRIVAL in errors, modifier = Modifier.fillMaxWidth())
                } else if (draft.deadline == CompanionDeadlineChoice.KEEP_ORIGINAL) {
                    val at = draft.source?.deadlineAtMillis
                    Text(at?.let { DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(Instant.ofEpochMilli(it).atZone(draft.zoneId)) }
                        ?: "시간 제한 없음", fontSize = 18.sp, lineHeight = 26.sp)
                }
                errors.values.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 16.sp, lineHeight = 24.sp) }
                Text("운전자가 하차할 위치를 확인하고 승인한 뒤 계획에 넣어요", fontSize = 16.sp, lineHeight = 24.sp)
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onDismiss, Modifier.weight(1f).heightIn(min = 56.dp), enabled = editingAllowed, shape = MaterialTheme.shapes.medium) { Text("닫기") }
                Button(onPrepare, Modifier.weight(2f).heightIn(min = 56.dp), enabled = editingAllowed, shape = MaterialTheme.shapes.medium) { Text("보낼 하차지 확인", fontSize = 16.sp) }
            }
        }
    }
}
