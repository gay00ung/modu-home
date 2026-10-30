package io.moduhome.app.feature.transfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.address.AddressCandidate
import io.moduhome.app.core.planning.VisitKind

@Composable
internal fun CompanionAddressPicker(
    query: String,
    candidates: List<AddressCandidate>,
    loading: Boolean,
    message: String?,
    selected: AddressCandidate?,
    kind: VisitKind,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (AddressCandidate) -> Unit,
    onViewLocation: (AddressCandidate) -> Unit,
    onKind: (VisitKind) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    editingAllowed: Boolean = true,
    purpose: CompanionAddressPurpose = CompanionAddressPurpose.ERRAND,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val visibleCandidates = candidates.take(5)
    val selectionValid = selected != null && selected in visibleCandidates
    val canSearch = editingAllowed && !loading && query.isNotBlank()
    val search: () -> Unit = {
        if (canSearch) {
            keyboard?.hide()
            onSearch()
        }
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (purpose == CompanionAddressPurpose.DROPOFF) "내 하차지 선택" else "주소로 새 볼일", modifier = Modifier.weight(1f), fontSize = 24.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("닫기", fontSize = 16.sp)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    label = { Text("주소", fontSize = 16.sp) },
                    placeholder = { Text("시·구·도로명·건물번호", fontSize = 16.sp) },
                    textStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
                    singleLine = true,
                    enabled = editingAllowed,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                )
                Button(onClick = search, enabled = canSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (loading) "주소 검색 중" else "주소 검색", fontSize = 16.sp)
                }
                Text("주소 기준 위치예요. 실제 출입구와 정차 위치를 확인해주세요",
                    fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (message != null) {
                    Text(message, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.primary)
                } else if (visibleCandidates.isEmpty()) {
                    Text("알고 있는 주소를 입력하고 검색해주세요", fontSize = 16.sp, lineHeight = 24.sp)
                }
                visibleCandidates.forEach { candidate ->
                    val chosen = selected == candidate
                    CompanionAddressCandidate(candidate, chosen, editingAllowed && !loading,
                        onClick = { onSelect(candidate) }, onViewLocation = { onViewLocation(candidate) })
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectionValid && purpose == CompanionAddressPurpose.ERRAND) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == VisitKind.PICKUP, onClick = { onKind(VisitKind.PICKUP) },
                            enabled = editingAllowed && !loading,
                            label = { Text("픽업", fontSize = 16.sp) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp))
                        FilterChip(selected = kind == VisitKind.GROCERIES, onClick = { onKind(VisitKind.GROCERIES) },
                            enabled = editingAllowed && !loading,
                            label = { Text("장보기", fontSize = 16.sp) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp))
                    }
                }
                Button(onClick = { keyboard?.hide(); onConfirm() },
                    enabled = editingAllowed && !loading && selectionValid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(when {
                        !selectionValid -> "주소를 선택해주세요"
                        kind == VisitKind.PICKUP -> "픽업 조건 정하기"
                        else -> "장보기 조건 정하기"
                    }, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CompanionAddressCandidate(
    candidate: AddressCandidate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onViewLocation: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(candidate.address, modifier = Modifier.weight(1f), fontSize = 20.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold)
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
        TextButton(onClick = onViewLocation, enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).heightIn(min = 56.dp)) {
            Text("지도에서 위치 확인", fontSize = 16.sp)
        }
    }
}
