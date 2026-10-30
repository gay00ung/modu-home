package io.moduhome.app.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.moduhome.app.core.address.AddressCandidate
import io.moduhome.app.core.transfer.CompanionErrandDraft
import io.moduhome.app.core.transfer.CompanionErrandField
import io.moduhome.app.core.transfer.CompanionErrandResult
import io.moduhome.app.ui.theme.ModuHomeTheme
import java.time.ZoneId

/** Rendering fixture only: no address service, preferences, relay, or native navigation. */
@Preview(name = "휴대폰 주소로 볼일 만들기", widthDp = 360, heightDp = 640)
@Composable
fun CompanionAddressPickerPreview() {
    val samples = remember {
        listOf(
            AddressCandidate.fromObservation("서울특별시 중구 세종대로 110 · 예시 주소", 37.5663, 126.9779)!!,
            AddressCandidate.fromObservation("서울특별시 중구 세종대로 99 · 예시 주소", 37.5657, 126.9775)!!,
        )
    }
    var state by remember { mutableStateOf(CompanionAddressState(query = "서울특별시 중구 세종대로 110")) }
    var draft by remember { mutableStateOf<CompanionErrandDraft?>(null) }
    var errors by remember { mutableStateOf<Map<CompanionErrandField, String>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(true) }
    ModuHomeTheme(vehicle = false) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("휴대폰 레이아웃 예시 · 실제 주소 검색·전송 없음")
            val current = draft
            if (current != null) {
                CompanionErrandEditor(current, editingAllowed = true,
                    onEdit = { draft = draft?.applyEdit(current, it); errors = emptyMap(); message = null },
                    onPrepare = {
                        when (val result = current.createProposal(System.currentTimeMillis(), ZoneId.systemDefault())) {
                            is CompanionErrandResult.Invalid -> errors = result.errors
                            is CompanionErrandResult.Created -> message = "예시 제안 생성 · 차량 전송 없음"
                        }
                    },
                    onDismiss = { draft = null; pickerOpen = true },
                    modifier = Modifier.size(360.dp, 640.dp), errors = errors, message = message,
                )
            } else if (pickerOpen) {
                CompanionAddressPicker(
                    query = state.query, candidates = state.candidates, loading = state.loading,
                    message = state.message, selected = state.selected, kind = state.kind,
                    onQuery = { state = state.clearResults().copy(query = it) },
                    onSearch = { state = state.clearResults("화면 확인용 예시 결과예요").copy(candidates = samples, resultQuery = state.query.trim()) },
                    onSelect = { state = state.copy(selected = it) },
                    onViewLocation = { state = state.copy(message = "이 미리보기에서는 지도 앱을 열지 않아요") },
                    onKind = { state = state.copy(kind = it) },
                    onConfirm = {
                        state.confirmedCandidate?.let {
                            draft = CompanionErrandDraft.forAddress(it.point, state.kind, System.currentTimeMillis(), ZoneId.systemDefault())
                            errors = emptyMap(); message = null
                        }
                    },
                    onDismiss = { pickerOpen = false },
                    modifier = Modifier.size(360.dp, 640.dp),
                )
            } else {
                Button(onClick = { pickerOpen = true }) { Text("주소 화면 다시 열기") }
            }
        }
    }
}
