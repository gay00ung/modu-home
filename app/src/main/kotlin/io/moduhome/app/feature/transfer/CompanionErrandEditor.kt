package io.moduhome.app.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.CompanionDeadlineChoice
import io.moduhome.app.core.transfer.CompanionErrandEdit
import io.moduhome.app.core.transfer.CompanionErrandDraft
import io.moduhome.app.core.transfer.CompanionErrandField
import io.moduhome.app.core.transfer.CompanionErrandSource
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun CompanionErrandEditor(
    draft: CompanionErrandDraft,
    editingAllowed: Boolean,
    onEdit: (CompanionErrandEdit) -> Unit,
    onPrepare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    errors: Map<CompanionErrandField, String> = emptyMap(),
    message: String? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val canKeepOriginalDeadline = draft.source is CompanionErrandSource.Received
    Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("조건 정해 보내기", modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                    Text("닫기", fontSize = 16.sp)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (draft.kind == VisitKind.PICKUP) "픽업" else "장보기",
                        fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(draft.place.name.ifBlank { "선택한 장소" }, fontSize = 20.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold)
                    if (draft.place.address.isNotBlank() && draft.place.address != draft.place.name) Text(draft.place.address,
                        fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("선택한 장소는 그대로 두고 조건만 정해요", fontSize = 16.sp, lineHeight = 24.sp)
                }
                message?.let { Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.primary) }
                listOf(CompanionErrandField.SOURCE, CompanionErrandField.PLACE, CompanionErrandField.CLOCK).forEach { field ->
                    errors[field]?.let { Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.error) }
                }
                CompanionMinutesField("머무는 시간", draft.stayMinutes, editingAllowed,
                    errors[CompanionErrandField.STAY], { onEdit(CompanionErrandEdit.Stay(it)) })
                CompanionMinutesField("차에서 장소까지 편도 도보", draft.walkingMinutesOneWay, editingAllowed,
                    errors[CompanionErrandField.WALKING], { onEdit(CompanionErrandEdit.Walking(it)) })
                Text("왕복 도보와 머무는 시간을 함께 비교해요", fontSize = 16.sp, lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (draft.kind == VisitKind.PICKUP) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .toggleable(value = draft.ready, enabled = editingAllowed, role = Role.Switch,
                            onValueChange = { onEdit(CompanionErrandEdit.Ready(it)) }),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("수령 준비됐어요", fontSize = 20.sp, lineHeight = 28.sp)
                            Text("직접 확인한 준비 상태를 알려주세요", fontSize = 16.sp, lineHeight = 24.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = draft.ready, onCheckedChange = null, enabled = editingAllowed)
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("완료 마감", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    if (canKeepOriginalDeadline) {
                        val originalDeadline = draft.originalDeadlineAtMillis?.let {
                            DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(Instant.ofEpochMilli(it).atZone(draft.zoneId))
                        } ?: "마감 없음"
                        Text("기존 마감 · $originalDeadline", fontSize = 16.sp, lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val dateFormat = DateTimeFormatter.ofPattern("M월 d일")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (canKeepOriginalDeadline) CompanionDeadlineOption("기존 마감 유지",
                            CompanionDeadlineChoice.KEEP_ORIGINAL, draft, editingAllowed, onEdit)
                        CompanionDeadlineOption("마감 없음", CompanionDeadlineChoice.NONE, draft, editingAllowed, onEdit)
                        CompanionDeadlineOption(dateFormat.format(draft.baseDate), CompanionDeadlineChoice.TODAY, draft, editingAllowed, onEdit)
                        CompanionDeadlineOption(dateFormat.format(draft.baseDate.plusDays(1)), CompanionDeadlineChoice.TOMORROW, draft, editingAllowed, onEdit)
                    }
                    if (draft.deadline == CompanionDeadlineChoice.TODAY || draft.deadline == CompanionDeadlineChoice.TOMORROW) {
                        OutlinedTextField(
                            value = draft.deadlineTime,
                            onValueChange = { onEdit(CompanionErrandEdit.Time(it)) },
                            label = { Text("완료 시각 (HH:mm)", fontSize = 16.sp) },
                            placeholder = { Text("18:30", fontSize = 20.sp) },
                            textStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
                            singleLine = true,
                            enabled = editingAllowed,
                            isError = errors.containsKey(CompanionErrandField.DEADLINE),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        )
                    }
                    errors[CompanionErrandField.DEADLINE]?.let {
                        Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (errors.isNotEmpty()) Text("입력한 조건을 다시 확인해주세요", fontSize = 16.sp, lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.error)
                Button(onClick = { keyboard?.hide(); onPrepare() }, enabled = editingAllowed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                    Text("보낼 조건 확인", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CompanionMinutesField(
    label: String,
    value: String,
    enabled: Boolean,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = value, onValueChange = onValueChange,
            label = { Text(label, fontSize = 16.sp) }, suffix = { Text("분", fontSize = 16.sp) },
            textStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp), singleLine = true,
            enabled = enabled, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
        error?.let { Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun CompanionDeadlineOption(
    label: String,
    choice: CompanionDeadlineChoice,
    draft: CompanionErrandDraft,
    enabled: Boolean,
    onEdit: (CompanionErrandEdit) -> Unit,
) {
    FilterChip(selected = draft.deadline == choice, onClick = { onEdit(CompanionErrandEdit.Deadline(choice)) },
        label = { Text(label, fontSize = 16.sp, lineHeight = 24.sp) }, enabled = enabled,
        modifier = Modifier.heightIn(min = 56.dp))
}
