package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@Preview(name = "가는김에 시간 한도 제안", widthDp = 628, heightDp = 855)
@Preview(name = "가는김에 시간 제안 큰 글씨", widthDp = 628, heightDp = 855, fontScale = 1.3f)
@Composable
fun VehicleAdditionalTimeLayoutPreview() {
    var state by remember {
        val sample = HomeUiState(JourneyDraft(budget = 20,
            pickup = VisitDraft(place = "예시 픽업 장소", stay = "5", ready = true),
            groceries = VisitDraft(place = "예시 장보기 장소", stay = "15")))
        mutableStateOf(sample.copy(progress = sample.progress.compare(sample.plans)))
    }
    VehicleLayoutSample(state, JourneyActions(onUseAdditionalTime = { suggestion ->
        state.withAdditionalTime(suggestion)?.let { next ->
            state = next.copy(progress = next.progress.compare(next.plans))
        }
    }))
}

@Preview(name = "가는김에 선택 전", widthDp = 628, heightDp = 855)
@Composable
fun VehicleUnselectedComparisonLayoutPreview() {
    val sample = HomeUiState(JourneyDraft(
        home = "예시 집", budget = 60,
        pickup = VisitDraft(place = "예시 픽업 장소", stay = "10", ready = true),
        groceries = VisitDraft(place = "예시 장보기 장소", stay = "20"),
    ))
    VehicleLayoutSample(sample.copy(progress = JourneyProgress(stage = JourneyStage.COMPARE)))
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
    val sample = HomeUiState(JourneyDraft(
        home = "예시 집", departure = departure, pickup = VisitDraft(place = "예시 픽업 장소", stay = "10"),
        groceries = VisitDraft(place = "예시 장보기 장소", stay = "20"),
    ))
    VehicleLayoutSample(sample.copy(progress = JourneyProgress(stage = stage, selectedPlanId = sample.plans.firstOrNull()?.id)))
}

@Composable
private fun VehicleLayoutSample(state: HomeUiState, actions: JourneyActions = JourneyActions()) {
    ModuHomeTheme(vehicle = true) {
        Column(Modifier.fillMaxSize()) {
            Text("화면 확인용 예시 · 실제 경로 조회 없음", fontSize = 18.sp, lineHeight = 26.sp)
            VehicleJourneyScreen(
                state = state,
                actions = actions, vehicle = VehicleNavigationUiState(),
                editingAllowed = true, restrictionMessage = null,
                onOpenDetails = {}, onOpenShared = {}, onOpenPhoneConnection = {}, onVoice = {},
                voiceAvailable = true, modifier = Modifier.weight(1f),
            )
        }
    }
}
