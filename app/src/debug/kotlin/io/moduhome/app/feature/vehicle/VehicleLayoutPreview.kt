package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyActions
import io.moduhome.app.feature.home.JourneyDraft
import io.moduhome.app.feature.home.VehicleNavigationUiState
import io.moduhome.app.feature.home.VisitDraft
import io.moduhome.app.feature.shared.SharedJourneyDraft
import io.moduhome.app.feature.shared.SharedVehicleScreen
import io.moduhome.app.feature.shared.SharedVehicleUiState
import io.moduhome.app.feature.shared.sharedLayoutActions
import io.moduhome.app.ui.theme.ModuHomeTheme

/** Read-only layout sample: no preferences, vehicle SDK, microphone, or network access. */
@Preview(name = "차량 가로 화면", widthDp = 1274, heightDp = 855)
@Preview(name = "차량 세로 화면", widthDp = 840, heightDp = 1184)
@Preview(name = "좁은 차량 입력 화면", widthDp = 628, heightDp = 855)
@Composable
fun VehicleLayoutPreview() {
    VehicleLayoutSample(JourneyStage.INPUT)
}

@Preview(name = "좁은 차량 비교 화면", widthDp = 628, heightDp = 855)
@Composable
fun VehicleComparisonLayoutPreview() {
    VehicleLayoutSample(JourneyStage.COMPARE)
}

@Preview(name = "좁은 차량 확인 화면", widthDp = 628, heightDp = 855)
@Composable
fun VehicleReviewLayoutPreview() {
    VehicleLayoutSample(JourneyStage.REVIEW)
}

@Preview(name = "자정을 넘기는 비교 화면", widthDp = 628, heightDp = 855)
@Composable
fun VehicleOvernightComparisonLayoutPreview() {
    VehicleLayoutSample(JourneyStage.COMPARE, departure = "23:30")
}

@Preview(name = "자정을 넘기는 확인 화면", widthDp = 628, heightDp = 855)
@Composable
fun VehicleOvernightReviewLayoutPreview() {
    VehicleLayoutSample(JourneyStage.REVIEW, departure = "23:30")
}

@Preview(name = "좁은 함께 귀가 비교 화면", widthDp = 628, heightDp = 855)
@Composable
fun SharedComparisonLayoutPreview() {
    ModuHomeTheme(vehicle = true) {
        Column(Modifier.fillMaxSize()) {
            Text("화면 배치 예시 · 실제 조작 없음", fontSize = 18.sp, lineHeight = 26.sp)
            SharedVehicleScreen(
                state = SharedVehicleUiState(SharedJourneyDraft(), stage = JourneyStage.COMPARE),
                actions = sharedLayoutActions,
                vehicle = VehicleNavigationUiState(),
                interaction = DrivingInteraction.Known(distractionOptimizationRequired = false),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VehicleLayoutSample(stage: JourneyStage, departure: String = "18:10") {
    ModuHomeTheme(vehicle = true) {
        Column(Modifier.fillMaxSize()) {
            Text("화면 배치 예시 · 실제 조작 없음", fontSize = 18.sp, lineHeight = 26.sp)
            val sample = HomeUiState(JourneyDraft(
                home = "예시 집", departure = departure, pickup = VisitDraft(place = "예시 픽업 장소", stay = "10"),
                groceries = VisitDraft(place = "예시 장보기 장소", stay = "20"),
            ))
            VehicleJourneyScreen(
                state = sample.copy(progress = JourneyProgress(stage = stage, selectedPlanId = sample.plans.firstOrNull()?.id)),
                actions = JourneyActions(), vehicle = VehicleNavigationUiState(),
                editingAllowed = true, restrictionMessage = null,
                onOpenDetails = {}, onOpenShared = {}, onOpenPhoneConnection = {}, onVoice = {},
                voiceAvailable = true, modifier = Modifier.weight(1f),
            )
        }
    }
}
