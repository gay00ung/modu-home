package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.planning.AdditionalTimeSuggestion

@Composable
internal fun VehicleAdditionalTimeSuggestion(
    suggestion: AdditionalTimeSuggestion,
    enabled: Boolean,
    onApply: (AdditionalTimeSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("현재 시간 한도에 맞는 순서가 없어요", fontSize = 30.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold)
            VehicleMetric("이 비교에서 필요한 최소 추가시간", "${suggestion.requiredMinutes}분")
            Text("현재 ${suggestion.allowedMinutes}분 한도보다 ${suggestion.increaseMinutes}분 더 필요해요",
                fontSize = 28.sp, lineHeight = 38.sp)
            Text("다른 조건은 유지하고, 현재 위치와 시각으로 다시 비교해요",
                fontSize = 28.sp, lineHeight = 38.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { if (enabled) onApply(suggestion) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), shape = RoundedCornerShape(18.dp)) {
                Text("추가 ${suggestion.requiredMinutes}분까지 허용하고 다시 비교", fontSize = 28.sp, lineHeight = 38.sp)
            }
        }
    }
}
