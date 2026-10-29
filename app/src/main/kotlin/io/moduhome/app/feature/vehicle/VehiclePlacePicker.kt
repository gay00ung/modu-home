package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.PlaceCatalogResult
import io.moduhome.app.feature.home.PlacePickerUiState

@Composable
internal fun VehiclePlacePicker(
    state: PlacePickerUiState,
    onChoose: (NavigationPoint) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "${state.role.label} 장소 선택",
) {
    val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.88f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.widthIn(max = 1200.dp).fillMaxWidth(0.86f).heightIn(max = maximumHeight),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(title, fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
                Text("차량 내비에 저장한 장소와 최근 목적지예요", fontSize = 28.sp, lineHeight = 38.sp)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (val result = state.result) {
                        null -> Text(if (state.loading) "장소를 불러오고 있어요" else "새로고침해 장소를 확인해주세요",
                            fontSize = 28.sp, lineHeight = 38.sp)
                        is PlaceCatalogResult.Failed -> Text(result.reason, fontSize = 28.sp, lineHeight = 38.sp,
                            color = MaterialTheme.colorScheme.error)
                        is PlaceCatalogResult.Loaded -> {
                            if (result.bookmarks.isEmpty() && result.recent.isEmpty()) {
                                Text("저장 장소와 최근 목적지가 없어요", fontSize = 30.sp, lineHeight = 40.sp,
                                    fontWeight = FontWeight.Medium)
                                Text("차량 내비에서 장소를 찾은 뒤 새로고침해주세요", fontSize = 28.sp, lineHeight = 38.sp)
                            }
                            if (result.bookmarks.isNotEmpty()) {
                                VehiclePlaceSection("저장 장소", result.bookmarks, !state.loading, onChoose)
                            }
                            if (result.bookmarks.isNotEmpty() && result.recent.isNotEmpty()) HorizontalDivider()
                            if (result.recent.isNotEmpty()) {
                                VehiclePlaceSection("최근 목적지", result.recent, !state.loading, onChoose)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("내비에서 추가했다면 새로고침하세요", Modifier.weight(1f), fontSize = 28.sp, lineHeight = 38.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.widthIn(min = 180.dp).heightIn(min = 88.dp),
                        shape = RoundedCornerShape(18.dp)) {
                        Text("닫기", fontSize = 28.sp)
                    }
                    Button(onClick = onRefresh, enabled = !state.loading,
                        modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                        Text(if (state.loading) "불러오는 중" else "새로고침", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun VehiclePlaceSection(
    title: String,
    places: List<NavigationPoint>,
    enabled: Boolean,
    onChoose: (NavigationPoint) -> Unit,
) {
    Text(title, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    places.forEach { point ->
        OutlinedButton(
            onClick = { if (enabled) onChoose(point) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
            shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(point.name, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (point.address.isNotBlank()) {
                    Text(point.address, fontSize = 28.sp, lineHeight = 38.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
