package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.planning.formatJourneyMinute
import io.moduhome.app.feature.home.ErrandAlternative

@Composable
internal fun VehicleErrandAlternatives(
    budgetMinutes: Int,
    options: List<ErrandAlternative>,
    enabled: Boolean,
    onCompare: () -> Unit,
    onSelect: (ErrandAlternative) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("시간은 그대로, 볼일은 한 가지만", fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
            Text("추가 ${budgetMinutes}분 한도 유지", fontSize = 30.sp, lineHeight = 40.sp,
                color = MaterialTheme.colorScheme.primary)
            if (options.isNotEmpty()) {
                val fontScale = LocalDensity.current.fontScale
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 1000.dp && fontScale <= 1.1f) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            options.forEach { option ->
                                AlternativeCard(option, enabled, onSelect, Modifier.weight(1f))
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            options.forEach { option -> AlternativeCard(option, enabled, onSelect, Modifier.fillMaxWidth()) }
                        }
                    }
                }
                Text("현재 위치에서 다시 조회한 예상이에요 · 실시간 교통 제외", fontSize = 28.sp, lineHeight = 38.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { if (enabled) onCompare() }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), shape = MaterialTheme.shapes.medium) {
                Text(if (options.isEmpty()) "픽업만·장보기만 비교" else "대안 경로 다시 조회",
                    fontSize = 28.sp, lineHeight = 38.sp)
            }
        }
    }
}

@Composable
private fun AlternativeCard(
    option: ErrandAlternative,
    enabled: Boolean,
    onSelect: (ErrandAlternative) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (option.kind) {
        VisitKind.PICKUP -> "픽업만"
        VisitKind.GROCERIES -> "장보기만"
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
            VehicleMetrics(listOf("집 도착 예상" to formatJourneyMinute(option.plan.homeArrivalMinute),
                "추가시간" to "+${option.plan.additionalMinutes}분"))
            VehiclePlanIssues(option.plan)
            Text(if (option.plan.isValid) "지금 한도와 볼일 조건에 맞아요" else "현재 조건으로는 선택할 수 없어요",
                fontSize = 28.sp, lineHeight = 38.sp)
            Button(onClick = { if (enabled && option.plan.isValid) onSelect(option) },
                enabled = enabled && option.plan.isValid, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                shape = MaterialTheme.shapes.medium) {
                Text("${title} 선택", fontSize = 28.sp, lineHeight = 38.sp)
            }
        }
    }
}
