package io.moduhome.app.feature.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.feature.home.VehicleNavigationUiState
import io.moduhome.app.feature.vehicle.VehicleActionBar
import io.moduhome.app.feature.vehicle.VehiclePageWidth

@Composable
internal fun SharedDepartureHome(
    state: SharedVehicleUiState,
    vehicle: VehicleNavigationUiState,
    actions: SharedVehicleActions,
    onOpenEditor: () -> Unit,
    onOpenBrowserDropoff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val confirmed = draft.passengers.filter(::dropoffConfirmed)
    val placesReady = confirmed.isNotEmpty() && confirmed.size == draft.passengers.size &&
        (draft.errand == null || draft.errand.place != null)
    val pending = state.pendingProposalCount
    val ready = vehicle.connection == NavigationConnection.Ready
    val busy = vehicle.planning || vehicle.sending || vehicle.reading
    val (actionLabel, onAction) = when {
        pending > 0 -> "받은 제안 보기" to actions.onOpenProposals
        confirmed.isNotEmpty() && draft.driverHome == null -> "내 집 선택" to actions.onChooseDriverHome
        placesReady && vehicle.connection is NavigationConnection.Failed -> "내비 다시 연결" to actions.onReconnect
        placesReady -> "방문 순서 비교" to actions.onCompare
        confirmed.isNotEmpty() -> "계획 이어가기" to onOpenEditor
        else -> "하차지 받기" to onOpenBrowserDropoff
    }
    val enabled = !busy && when (actionLabel) {
        "내 집 선택" -> ready
        "방문 순서 비교" -> ready && vehicle.roadComparisonAvailable
        else -> true
    }
    val copy: @Composable () -> Unit = {
        Text(
            when {
                pending > 0 -> "받은 제안이 있어요"
                confirmed.isNotEmpty() -> "하차지 ${confirmed.size}곳을 확인했어요"
                else -> "어디에서 내려줄까요?"
            },
            fontSize = 56.sp,
            lineHeight = 66.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                pending > 0 -> "정차할 수 있는 곳인지 확인하고 계획에 넣어요"
                confirmed.isNotEmpty() && draft.driverHome == null -> "마지막 목적지로 내 집을 선택해주세요"
                placesReady -> "누구부터 내려줄지 도착 시간을 비교해요"
                confirmed.isNotEmpty() -> "남은 하차지나 볼일 조건을 확인해주세요"
                else -> "친구가 내릴 곳을 휴대폰으로 보내요"
            },
            fontSize = 30.sp, lineHeight = 40.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (confirmed.isNotEmpty()) {
            confirmed.forEach { person ->
                Text("${person.name}  ${person.dropoff?.name.orEmpty()}", fontSize = 28.sp, lineHeight = 38.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (actionLabel != "하차지 받기") DepartureSecondary("하차지 받기", !busy, onOpenBrowserDropoff)
            DepartureSecondary("차량에서 직접 설정", !busy, onOpenEditor)
        }
    }
    Scaffold(
        modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.padding(horizontal = 40.dp, vertical = 20.dp).widthIn(max = VehiclePageWidth).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("모두 집으로", Modifier.weight(1f), fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text("정차 중", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            fontSize = 28.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                HorizontalDivider()
            }
        },
        bottomBar = {
            VehicleActionBar(
                caption = draft.driverHome?.let { "마지막 목적지  ${it.name}" } ?: "친구가 지정한 하차지까지 안내해요",
                actionLabel = if (vehicle.planning) "방문 순서 확인 중" else actionLabel,
                actionEnabled = enabled,
                onAction = onAction,
                secondaryAction = { DepartureSecondary("혼자 가는김에", !busy, actions.onLeave) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vehicle.message?.takeIf { vehicle.error }?.let {
                Text(it, fontSize = 28.sp, lineHeight = 38.sp, color = MaterialTheme.colorScheme.error)
            }
            val wideText = LocalDensity.current.fontScale <= 1.15f
            BoxWithConstraints(Modifier.widthIn(max = VehiclePageWidth).fillMaxWidth()) {
                if (maxWidth >= 800.dp && wideText) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { copy() }
                        DepartureIllustration(Modifier.weight(0.9f).height(332.dp))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { copy() }
                }
            }
        }
    }
}

@Composable
private fun DepartureSecondary(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, modifier = Modifier.heightIn(min = 80.dp), enabled = enabled) {
        Text(label, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DepartureIllustration(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
        Canvas(Modifier.fillMaxSize()) {
            val start = Offset(size.width * 0.19f, size.height * 0.36f)
            val middle = Offset(size.width * 0.49f, size.height * 0.32f)
            val home = Offset(size.width * 0.79f, size.height * 0.69f)
            val radius = minOf(size.width, size.height) * 0.15f
            val route = Path().apply {
                moveTo(start.x, start.y)
                quadraticTo((start.x + middle.x) / 2f, start.y * 0.6f, middle.x, middle.y)
                quadraticTo(home.x, middle.y, home.x, home.y)
            }
            drawPath(route, colors.primary.copy(alpha = 0.68f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(colors.secondaryContainer, radius, start)
            drawCircle(colors.surface, radius, middle)
            drawCircle(colors.primary, radius, home)
            drawCircle(colors.onSecondaryContainer, radius * 0.31f, start, style = Stroke(3.dp.toPx()))
            drawCircle(colors.primary, radius * 0.31f, middle, style = Stroke(3.dp.toPx()))
            val house = Path().apply {
                moveTo(home.x - radius * 0.47f, home.y - radius * 0.06f)
                lineTo(home.x, home.y - radius * 0.46f)
                lineTo(home.x + radius * 0.47f, home.y - radius * 0.06f)
                lineTo(home.x + radius * 0.47f, home.y + radius * 0.42f)
                lineTo(home.x - radius * 0.47f, home.y + radius * 0.42f)
                close()
            }
            drawPath(house, colors.onPrimary, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
            drawRect(colors.onPrimary, Offset(home.x - radius * 0.12f, home.y + radius * 0.12f), Size(radius * 0.24f, radius * 0.29f))
        }
    }
}
