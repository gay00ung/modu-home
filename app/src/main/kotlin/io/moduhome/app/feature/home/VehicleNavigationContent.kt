package io.moduhome.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.PlaceCatalogResult
import io.moduhome.app.core.planning.JourneyStage

internal data class VehicleNavigationUiState(
    val connection: NavigationConnection = NavigationConnection.Unsupported,
    val sending: Boolean = false,
    val reading: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val snapshot: NavigationSnapshot? = null,
)

internal data class PlacePickerUiState(
    val role: NavigationPlaceRole,
    val loading: Boolean = true,
    val result: PlaceCatalogResult? = null,
)

@Composable
internal fun VehicleNavigationContent(state: HomeUiState, vehicle: VehicleNavigationUiState, actions: JourneyActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("차량 내비", subtitle = when (val connection = vehicle.connection) {
            NavigationConnection.Unsupported -> "차량 내비 연결은 Pleos Connect에서 사용할 수 있어요"
            NavigationConnection.Connecting -> "차량 내비에 연결하고 있어요"
            NavigationConnection.Ready -> "연결됨 · 현재 차량 위치에서 출발해요"
            is NavigationConnection.Failed -> connection.reason
        })
        if (vehicle.connection == NavigationConnection.Connecting || vehicle.sending || vehicle.reading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (vehicle.connection is NavigationConnection.Failed) {
            OutlinedButton(onClick = actions.onReconnectNavigation) { Text("다시 연결") }
        }
        if (state.progress.stage == JourneyStage.INPUT && vehicle.connection != NavigationConnection.Unsupported) {
            NavigationPlaceRole.entries.filter { role ->
                when (role) {
                    NavigationPlaceRole.HOME -> true
                    NavigationPlaceRole.PICKUP -> state.draft.pickup.enabled
                    NavigationPlaceRole.GROCERIES -> state.draft.groceries.enabled
                }
            }.forEach { role ->
                val point = state.draft.navigationPoint(role)
                OutlinedButton(
                    onClick = { actions.onChooseNavigationPlace(role) },
                    enabled = vehicle.connection == NavigationConnection.Ready,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(role.label + " · " + (point?.name ?: "내비 장소 선택"), fontWeight = FontWeight.SemiBold)
                        if (point != null) Text(point.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("내비에 저장한 장소나 최근 목적지를 선택해주세요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vehicle.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = if (vehicle.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        if (vehicle.sending) Text("목적지와 경유 순서를 확인하고 있어요", style = MaterialTheme.typography.bodyMedium)
        vehicle.snapshot?.let { snapshot ->
            HorizontalDivider()
            Text("차량 내비의 안내 순서", style = MaterialTheme.typography.labelLarge)
            if (!snapshot.guidanceActive) Text("현재 길안내 중이 아니에요", style = MaterialTheme.typography.bodySmall)
            snapshot.waypoints.forEachIndexed { index, point ->
                Text((index + 1).toString() + " · " + point.name, style = MaterialTheme.typography.bodyMedium)
            }
            Text("목적지 · " + (snapshot.destination?.name ?: "설정된 목적지 없음"),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        if (state.progress.stage == JourneyStage.ACTIVE && vehicle.connection == NavigationConnection.Ready) {
            TextButton(onClick = actions.onReadNavigation, enabled = !vehicle.reading && !vehicle.sending) {
                Text("차량 내비 다시 확인")
            }
        }
    }
}

@Composable
internal fun NavigationPlacePicker(
    state: PlacePickerUiState,
    onChoose: (NavigationPoint) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.role.label + " 장소 선택") },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("차량 내비에서 장소를 검색하거나 저장한 뒤 새로고침하면 여기서 선택할 수 있어요")
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (val result = state.result) {
                    null -> Unit
                    is PlaceCatalogResult.Failed -> Text(result.reason, color = MaterialTheme.colorScheme.error)
                    is PlaceCatalogResult.Loaded -> {
                        if (result.bookmarks.isEmpty() && result.recent.isEmpty()) Text("저장 장소와 최근 목적지가 없어요")
                        if (result.bookmarks.isNotEmpty()) {
                            Text("저장 장소", style = MaterialTheme.typography.labelLarge)
                            result.bookmarks.forEach { PlaceChoice(it, onChoose) }
                        }
                        if (result.recent.isNotEmpty()) {
                            Text("최근 목적지", style = MaterialTheme.typography.labelLarge)
                            result.recent.forEach { PlaceChoice(it, onChoose) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh, enabled = !state.loading) { Text("새로고침") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun PlaceChoice(point: NavigationPoint, onChoose: (NavigationPoint) -> Unit) {
    OutlinedButton(onClick = { onChoose(point) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(point.name, fontWeight = FontWeight.SemiBold)
            if (point.address.isNotEmpty()) Text(point.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}
