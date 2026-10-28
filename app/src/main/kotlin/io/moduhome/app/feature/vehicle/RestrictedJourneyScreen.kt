package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.core.vehicle.restrictContent
import io.moduhome.app.feature.home.HomeUiState

/** The OS may cover this Activity while driving; never declares or overrides platform approval. */
@Composable
internal fun RestrictedJourneyScreen(state: HomeUiState, interaction: DrivingInteraction, modifier: Modifier = Modifier) {
    val plan = state.selectedPlan
    val next = plan?.visits?.getOrNull(state.progress.completedVisits)
    val content = when {
        interaction is DrivingInteraction.Unknown -> listOf(interaction.reason)
        state.progress.stage == JourneyStage.ACTIVE -> listOf(
            "차량 내비를 따라가세요",
            next?.visit?.place ?: state.draft.home,
            "계획 변경과 완료 확인은 정차 후에",
        )
        else -> listOf("정차 후 계획을 선택해주세요", "계획은 그대로 보관하고 있어요")
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(56.dp),
            verticalArrangement = Arrangement.Center) {
            interaction.restrictContent(content).forEachIndexed { index, text ->
                Text(text, modifier = Modifier.padding(vertical = 28.dp),
                    fontSize = if (index == 1) 52.sp else 36.sp,
                    lineHeight = if (index == 1) 66.sp else 48.sp,
                    fontWeight = if (index == 1) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
