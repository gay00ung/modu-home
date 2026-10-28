package io.moduhome.app.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun JourneyInputContent(state: HomeUiState, onChange: (JourneyDraft) -> Unit) {
    val draft = state.draft
    val errors = if (state.showErrors) state.validation.errors else emptyMap()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("어디서 집으로 가나요?", subtitle = "출발일 ${LocalDate.ofEpochDay(draft.dateEpochDay).format(DateTimeFormatter.ofPattern("M월 d일"))}")
        JourneyField(draft.origin, { onChange(draft.copy(origin = it)) }, "출발지", Modifier.fillMaxWidth(), errors["origin"], placeholder = "장소명 또는 주소")
        JourneyField(draft.home, { onChange(draft.copy(home = it)) }, "집", Modifier.fillMaxWidth(), errors["home"], placeholder = "장소명 또는 주소")
        JourneyField(draft.departure, { onChange(draft.copy(departure = it.take(5))) }, "출발 시각", Modifier.width(170.dp), errors["departure"], placeholder = "18:10")
    }
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("가는김에 처리할 일", subtitle = "최대 두 건을 한 계획으로 묶어요")
        VisitEditor("주문한 물건 픽업", "pickup", draft.pickup, errors, { onChange(draft.copy(pickup = it)) }, pickup = true)
        VisitEditor("미뤄둔 장보기", "groceries", draft.groceries, errors, { onChange(draft.copy(groceries = it)) }, pickup = false)
        errors["visits"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("귀가까지 허용할 추가시간", subtitle = "곧바로 집에 갈 때보다 얼마나 더 쓸까요?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(10, 20, 30, 60).forEach { minutes ->
                FilterChip(selected = draft.budget == minutes, onClick = { onChange(draft.copy(budget = minutes)) },
                    label = { Text("${minutes}분") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
    HorizontalDivider()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "예상 주행시간 접기 −" else "예상 주행시간 조정 +")
        }
        Text("이동시간은 직접 설정한 예상값을 사용해요", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded || errors.keys.any { it.startsWith("travel.") }) {
            TravelLeg.entries.forEach { leg ->
                val relevant = when (leg) {
                    TravelLeg.DIRECT -> true
                    TravelLeg.ORIGIN_PICKUP, TravelLeg.PICKUP_HOME -> draft.pickup.enabled
                    TravelLeg.ORIGIN_GROCERIES, TravelLeg.GROCERIES_HOME -> draft.groceries.enabled
                    TravelLeg.PICKUP_GROCERIES, TravelLeg.GROCERIES_PICKUP -> draft.pickup.enabled && draft.groceries.enabled
                }
                if (relevant) JourneyField(draft.travel[leg].orEmpty(), {
                    onChange(draft.copy(travel = draft.travel + (leg to it)))
                }, leg.label, Modifier.fillMaxWidth(), errors["travel.${leg.name}"], numeric = true)
            }
        }
    }
}

@Composable
private fun VisitEditor(
    title: String,
    key: String,
    draft: VisitDraft,
    errors: Map<String, String>,
    onChange: (VisitDraft) -> Unit,
    pickup: Boolean,
) {
    val border = if (draft.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (draft.enabled) 1.5.dp else 1.dp, border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(draft.enabled, role = Role.Checkbox, onValueChange = { onChange(draft.copy(enabled = it)) }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(draft.enabled, onCheckedChange = null)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (draft.enabled) {
                JourneyField(draft.place, { onChange(draft.changePlace(it)) }, "들를 곳", Modifier.fillMaxWidth(), errors["$key.place"], placeholder = "장소명 또는 주소")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    JourneyField(draft.stay, { onChange(draft.copy(stay = it)) }, "매장 체류", Modifier.weight(1f), errors["$key.stay"], numeric = true)
                    JourneyField(draft.walking, { onChange(draft.copy(walking = it)) }, "도보 편도", Modifier.weight(1f), errors["$key.walking"], numeric = true)
                }
                if (pickup) {
                    Row(Modifier.fillMaxWidth().toggleable(draft.ready, role = Role.Checkbox, onValueChange = { onChange(draft.copy(ready = it)) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.ready, onCheckedChange = null)
                        Text("수령 준비를 확인했어요", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                var options by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { options = !options }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (options) "마감 설정 접기 −" else "${if (pickup) "수령" else "장보기"} 마감 설정 +")
                }
                if (options || draft.deadline.isNotEmpty() || errors["$key.deadline"] != null) {
                    JourneyField(draft.deadline, { onChange(draft.copy(deadline = it.take(5))) },
                        "${if (pickup) "수령" else "장보기"} 완료 마감", Modifier.fillMaxWidth(), errors["$key.deadline"], placeholder = "선택 · 20:00")
                    if (draft.deadline.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!draft.deadlineNextDay, { onChange(draft.copy(deadlineNextDay = false)) }, { Text("출발일") })
                        FilterChip(draft.deadlineNextDay, { onChange(draft.copy(deadlineNextDay = true)) }, { Text("다음날") })
                    }
                }
            }
        }
    }
}
