package io.moduhome.app.feature.shared

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
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DrivingLegKey
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.SharedDrivingEstimate
import io.moduhome.app.core.shared.SharedJourneyPlanner
import io.moduhome.app.core.shared.TravelEstimateKind
import io.moduhome.app.core.shared.TravelEvidence
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.feature.home.VehicleNavigationUiState
import io.moduhome.app.ui.theme.ModuHomeTheme
import java.time.LocalDate
import java.time.ZoneId

@Preview(name = "함께 귀가 세 계획", widthDp = 628, heightDp = 855)
@Composable
fun SharedPlansLayoutPreview() {
    SharedLayoutSample(sharedLayoutState(JourneyStage.COMPARE))
}

@Preview(name = "함께 귀가 다음 세 계획", widthDp = 628, heightDp = 855)
@Composable
fun SharedNextPlansLayoutPreview() {
    SharedLayoutSample(sharedLayoutState(JourneyStage.COMPARE, page = 1))
}

@Preview(name = "함께 귀가 선택 전", widthDp = 628, heightDp = 855)
@Composable
fun SharedUnselectedPlansLayoutPreview() {
    SharedLayoutSample(sharedLayoutState(JourneyStage.COMPARE).copy(selectedPlanId = null))
}

@Preview(name = "함께 귀가 긴 장소명 확인", widthDp = 628, heightDp = 855)
@Composable
fun SharedReviewLayoutPreview() {
    SharedLayoutSample(sharedLayoutState(JourneyStage.REVIEW))
}

@Preview(name = "함께 귀가 시간 한도 제안", widthDp = 628, heightDp = 855)
@Preview(name = "함께 귀가 시간 제안 큰 글씨", widthDp = 628, heightDp = 855, fontScale = 1.3f)
@Composable
fun SharedAdditionalTimeLayoutPreview() {
    var state by remember { mutableStateOf(sharedLayoutState(JourneyStage.COMPARE, budget = 20)) }
    SharedLayoutSample(state, sharedLayoutActions.copy(onUseAdditionalTime = { suggestion ->
        if (state.additionalTimeSuggestion == suggestion) {
            state = sharedLayoutState(JourneyStage.COMPARE, budget = suggestion.requiredMinutes)
        }
    }))
}

@Composable
private fun SharedLayoutSample(state: SharedVehicleUiState, actions: SharedVehicleActions = sharedLayoutActions) {
    ModuHomeTheme(vehicle = true) {
        Column(Modifier.fillMaxSize()) {
            Text("화면 확인용 예시 · 실제 경로 조회 없음", fontSize = 18.sp, lineHeight = 26.sp)
            SharedVehicleScreen(state, actions, VehicleNavigationUiState(),
                DrivingInteraction.Known(distractionOptimizationRequired = false), Modifier.weight(1f))
        }
    }
}

/** Fixed sample times and places; no preferences, vehicle SDK, clock, or network access. */
private fun sharedLayoutState(stage: JourneyStage, page: Int = 0, budget: Int = 60): SharedVehicleUiState {
    val zone = ZoneId.of("Asia/Seoul")
    val departure = LocalDate.of(2026, 1, 1).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
    val verification = DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "화면 배치 예시 확인", 1L)
    val draft = SharedJourneyDraft(
        driverHome = NavigationPoint("예시 운전자 집 공동주택 후문 승하차장", 37.5, 127.0),
        passengers = (1..2).map { number ->
            SharedPassengerDraft("passenger-$number", "동승자 $number",
                NavigationPoint("예시 동승자 $number 집 앞 북측 출입구 승하차장", 37.5, 127.0),
                doorstepDropoffConfirmed = true, doorstepConfirmedAtMillis = 1L, verification = verification)
        },
        errand = SharedErrandDraft(VisitKind.PICKUP,
            NavigationPoint("예시 픽업 매장 중앙주차장 1층 출입구", 37.5, 127.0), stayMinutes = 10, ready = true),
        allowedAdditionalMinutes = budget,
    )
    val request = requireNotNull(draft.validate(NavigationPoint("예시 출발지", 37.5, 127.0), departure, zone).request)
    val places = listOf(request.origin, request.home) + request.passengers.flatMap { p -> p.candidates.map { it.place } } +
        listOfNotNull(request.errand?.place)
    val sample = TravelEvidence(TravelEstimateKind.SAMPLE, "화면 배치용 예시시간")
    val estimates = places.flatMap { from ->
        places.filter { it.id != from.id }.map { to ->
            DrivingLegKey(from.id, to.id) to SharedDrivingEstimate(
                if (from == request.origin && to == request.home) 20 else 10, sample)
        }
    }.toMap()
    val plans = SharedJourneyPlanner().compare(request.copy(drivingEstimates = estimates))
    return SharedVehicleUiState(draft, stage = stage, plans = plans, selectedPlanId = plans.first().id,
        planPage = page, estimateLabel = sample.source, directHomeMinutes = 20,
        additionalTimeSuggestion = if (stage == JourneyStage.COMPARE) {
            SharedJourneyPlanner().suggestAdditionalTime(plans, budget)
        } else null)
}

internal val sharedLayoutActions = SharedVehicleActions(
    onDraftChange = {}, onPassengerCountChange = {}, onChooseDriverHome = {},
    onChoosePassengerDropoff = {}, onErrandChoice = {}, onChooseErrandPlace = {},
    onConfirmDropoff = { _, _ -> }, onCompare = {}, onUseAdditionalTime = {}, onSelectPlan = {}, onPlanPageChange = {},
    onReview = {}, onStart = {}, onCompleteNextStop = {}, onConfirmPassedStop = {},
    onRetryNavigation = {}, onFinish = {}, onBack = {}, onEdit = {}, onNewJourney = {},
    onCancel = {}, onLeave = {}, onReconnect = {}, onOpenProposals = {}, onOpenPhoneConnection = {},
)
