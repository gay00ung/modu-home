package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun VehicleMetrics(metrics: List<Pair<String, String>>, modifier: Modifier = Modifier, spacing: Dp = 24.dp) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val valueStyle = LocalTextStyle.current.copy(fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        metrics.forEach { (label, value) ->
            val textWidth = with(density) { textMeasurer.measure(value, valueStyle, softWrap = false).size.width.toDp() }
            val minimumWidth = maxOf(200.dp * density.fontScale, textWidth)
            // 날짜가 붙은 시각도 같은 줄에 들어갈 너비로 배분한다
            VehicleMetric(label, value, Modifier.widthIn(min = minimumWidth).weight(minimumWidth.value))
        }
    }
}

@Composable
internal fun VehicleMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 28.sp, lineHeight = 38.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}
