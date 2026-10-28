package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.GuidanceEstimateResult
import io.moduhome.app.core.planning.ErrandJourneyPlan
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.PlanIssueKind
import io.moduhome.app.core.planning.formatJourneyMinute
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyActions
import io.moduhome.app.feature.home.NavigationPlaceRole
import io.moduhome.app.feature.home.VehicleNavigationUiState
import io.moduhome.app.feature.home.VisitDraft
import io.moduhome.app.feature.home.planTitle
import io.moduhome.app.feature.home.visitLabel

/** The app owns the journey and restriction state. This surface only renders it and emits intents. */
@Composable
internal fun VehicleJourneyScreen(
    state: HomeUiState,
    actions: JourneyActions,
    vehicle: VehicleNavigationUiState,
    editingAllowed: Boolean,
    restrictionMessage: String?,
    onOpenDetails: () -> Unit,
    onVoice: () -> Unit,
    voiceAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val stage = state.progress.stage
    val busy = vehicle.sending || vehicle.reading || vehicle.planning
    val canEdit = editingAllowed && !busy
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 40.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(vehicleTitle(stage), fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold)
                        VehicleCaption(vehicleStage(stage))
                    }
                    if (stage == JourneyStage.INPUT) {
                        OutlinedButton(onClick = { if (canEdit) actions.onOpenProposals() }, enabled = canEdit,
                            modifier = Modifier.heightIn(min = 80.dp), shape = RoundedCornerShape(18.dp)) {
                            Text("동승자 볼일", fontSize = 24.sp)
                        }
                        if (voiceAvailable) {
                            OutlinedButton(onClick = { if (canEdit) onVoice() }, enabled = canEdit,
                                modifier = Modifier.heightIn(min = 80.dp), shape = RoundedCornerShape(18.dp)) {
                                Text("말로 선택", fontSize = 24.sp)
                            }
                        }
                        TextButton(onClick = { if (canEdit) onOpenDetails() }, enabled = canEdit,
                            modifier = Modifier.heightIn(min = 80.dp)) {
                            Text("상세 조건", fontSize = 24.sp)
                        }
                    }
                }
                if (!editingAllowed || restrictionMessage != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                        Text(restrictionMessage ?: "정차 후 계획을 선택하거나 바꿀 수 있어요",
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                            fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (vehicle.error && vehicle.message != null) VehicleError(vehicle.message)
            }
        },
        bottomBar = { VehicleFooter(state, actions, vehicle, canEdit) },
    ) { padding ->
        key(stage) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                when (stage) {
                    JourneyStage.INPUT -> VehicleInput(state, actions, vehicle, canEdit)
                    JourneyStage.COMPARE -> VehicleComparison(state, actions, canEdit)
                    JourneyStage.REVIEW -> VehicleReview(state)
                    JourneyStage.ACTIVE -> VehicleActive(state, vehicle, actions, canEdit)
                    JourneyStage.COMPLETE -> VehicleComplete(state)
                }
                VehicleNavigationStatus(vehicle, actions, canEdit)
            }
        }
    }
}

@Composable
private fun VehicleInput(state: HomeUiState, actions: JourneyActions, vehicle: VehicleNavigationUiState, canEdit: Boolean) {
    val draft = state.draft
    val canChoosePlace = canEdit && vehicle.connection == NavigationConnection.Ready
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleCaption("마지막 목적지 · 집")
                Text(draft.navigationPoint(NavigationPlaceRole.HOME)?.name ?: "어디로 돌아갈까요?",
                    fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { if (canChoosePlace) actions.onChooseNavigationPlace(NavigationPlaceRole.HOME) },
                enabled = canChoosePlace, modifier = Modifier.heightIn(min = 80.dp).widthIn(min = 220.dp),
                shape = RoundedCornerShape(18.dp)) {
                Text(if (draft.navigationPoint(NavigationPlaceRole.HOME) == null) "집 선택" else "집 변경", fontSize = 28.sp)
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        VehicleVisitCard(
            title = "픽업", visit = draft.pickup,
            placeName = draft.navigationPoint(NavigationPlaceRole.PICKUP)?.name,
            canEdit = canEdit, canChoosePlace = canChoosePlace,
            onChange = { actions.onDraftChange(draft.copy(pickup = it)) },
            onPlace = { actions.onChooseNavigationPlace(NavigationPlaceRole.PICKUP) },
            pickup = true, modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        VehicleVisitCard(
            title = "장보기", visit = draft.groceries,
            placeName = draft.navigationPoint(NavigationPlaceRole.GROCERIES)?.name,
            canEdit = canEdit, canChoosePlace = canChoosePlace,
            onChange = { actions.onDraftChange(draft.copy(groceries = it)) },
            onPlace = { actions.onChooseNavigationPlace(NavigationPlaceRole.GROCERIES) },
            pickup = false, modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("더 걸려도 괜찮은 시간", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                VehicleCaption("집 직행보다 최대 ${draft.budget}분 더")
            }
            listOf(10, 20, 30, 60).forEach { minutes ->
                VehicleChoice("${minutes}분", draft.budget == minutes, canEdit,
                    onClick = { if (canEdit) actions.onDraftChange(draft.copy(budget = minutes)) },
                    modifier = Modifier.widthIn(min = 132.dp))
            }
        }
    }
    if (state.showErrors) {
        state.validation.errors.values.distinct().take(3).forEach { message -> VehicleError(message) }
    }
    if (vehicle.roadComparisonAvailable && vehicle.connection == NavigationConnection.Ready) {
        VehicleCaption("현재 차량 위치에서 집과 들를 곳까지의 도로 경로를 비교해요")
    } else {
        VehicleCaption("집과 들를 곳을 선택하면 현재 차량 위치에서 경로를 비교해요")
    }
}

@Composable
private fun VehicleVisitCard(
    title: String,
    visit: VisitDraft,
    placeName: String?,
    canEdit: Boolean,
    canChoosePlace: Boolean,
    onChange: (VisitDraft) -> Unit,
    onPlace: () -> Unit,
    pickup: Boolean,
    modifier: Modifier = Modifier,
) {
    val enabled = canEdit && visit.enabled
    Surface(modifier, color = if (visit.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, Modifier.weight(1f), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                VehicleCaption(if (visit.enabled) "들를게요" else "다음에")
                Switch(checked = visit.enabled, onCheckedChange = { if (canEdit) onChange(visit.copy(enabled = it)) },
                    enabled = canEdit, modifier = Modifier.width(100.dp).heightIn(min = 80.dp))
            }
            OutlinedButton(onClick = { if (enabled && canChoosePlace) onPlace() }, enabled = enabled && canChoosePlace,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                Text(placeName ?: "장소 선택", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VehicleCaption("체류", Modifier.width(62.dp))
                listOf(5, 10, 15).forEach { minutes ->
                    VehicleChoice("${minutes}분", visit.stay.toIntOrNull() == minutes, enabled,
                        onClick = { if (enabled) onChange(visit.copy(stay = minutes.toString())) },
                        modifier = Modifier.weight(1f))
                }
            }
            if (pickup) {
                OutlinedButton(onClick = { if (enabled) onChange(visit.copy(ready = !visit.ready)) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (visit.ready) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)) {
                    Text(if (visit.ready) "✓ 수령 준비됐어요" else "픽업 준비 확인", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.CenterStart) {
                    VehicleCaption("장보는 시간을 포함해 비교해요")
                }
            }
        }
    }
}

@Composable
private fun VehicleComparison(state: HomeUiState, actions: JourneyActions, canEdit: Boolean) {
    if (state.plans.isEmpty()) {
        VehicleError("비교할 계획이 없어요. 장소와 조건을 다시 확인해주세요")
        return
    }
    val recommended = state.plans.firstOrNull { it.isValid }?.id
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        state.plans.forEach { plan ->
            val selected = state.progress.selectedPlanId == plan.id
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight().selectable(selected, enabled = canEdit,
                    role = Role.RadioButton, onClick = { if (canEdit) actions.onSelect(plan.id) }),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (selected) "✓" else "○", fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
                        VehicleCaption(when {
                            plan.id == recommended -> "도로망 기준 추천 · 교통 제외"
                            plan.isValid -> "도로망 기준으로 조건에 맞아요"
                            else -> "조건 확인 필요"
                        })
                    }
                    Text(planTitle(plan), fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        VehicleMetric("교통 제외 도착", formatJourneyMinute(plan.homeArrivalMinute), Modifier.weight(1f))
                        VehicleMetric("교통 제외 추가", "+${plan.additionalMinutes}분", Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    plan.visits.forEachIndexed { index, visit ->
                        Text("${index + 1}. ${visit.visit.place}", fontSize = 30.sp, lineHeight = 40.sp,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    VehiclePlanIssues(plan)
                    if (plan.isValid) VehicleCaption(if (selected) "선택한 계획" else "눌러서 이 순서 선택")
                }
            }
        }
    }
    VehicleEstimateSource(state)
}

@Composable
private fun VehicleReview(state: HomeUiState) {
    val plan = state.selectedPlan
    if (plan == null) {
        VehicleError("확정할 계획을 다시 선택해주세요")
        return
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                VehicleMetric("교통 제외 도착", formatJourneyMinute(plan.homeArrivalMinute), Modifier.weight(1f))
                VehicleMetric("교통 제외 추가", "+${plan.additionalMinutes}분", Modifier.weight(1f))
                VehicleMetric("들를 곳", "${plan.visits.size}곳", Modifier.weight(1f))
            }
            HorizontalDivider()
            plan.visits.forEachIndexed { index, scheduled ->
                VehicleStopLine("${index + 1}", scheduled.visit.place, visitLabel(scheduled.visit.kind),
                    formatJourneyMinute(scheduled.arrivalMinute))
            }
            VehicleStopLine("집", state.draft.home, "마지막 목적지", formatJourneyMinute(plan.homeArrivalMinute))
        }
    }
    VehiclePlanIssues(plan)
    VehicleEstimateSource(state)
}

@Composable
private fun VehicleActive(state: HomeUiState, vehicle: VehicleNavigationUiState, actions: JourneyActions, canEdit: Boolean) {
    val plan = state.selectedPlan
    if (plan == null) {
        VehicleError("저장된 계획을 확인하지 못했어요. 차량 내비의 안내 상태를 확인해주세요")
        return
    }
    val next = plan.visits.getOrNull(state.progress.completedVisits)
    val guidance = (vehicle.guidanceResult as? GuidanceEstimateResult.Success)?.estimate
        ?.takeIf { it.isFresh(vehicle.tripNow) }
    val nextGuided = guidance?.let { it.waypoints.firstOrNull() ?: it.destination }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.fillMaxWidth().padding(36.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1.7f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                VehicleCaption(if (nextGuided != null) "차량 내비가 안내하는 다음 장소" else
                    if (next == null) "볼일을 마쳤어요 · 이제 집으로" else "계획상 다음 들를 곳")
                Text(nextGuided?.point?.name ?: next?.visit?.place ?: state.draft.home, fontSize = 48.sp, lineHeight = 60.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (nextGuided != null) "내비 기준 약 ${(nextGuided.remainingSeconds.toLong() + 59) / 60}분" else
                    if (next == null) "도착 후 귀가를 마무리해주세요" else
                        "${visitLabel(next.visit.kind)} · 체류 ${next.visit.stayMinutes}분", fontSize = 28.sp, lineHeight = 36.sp)
            }
            VehicleMetric("집까지 남은 주행", guidance?.let { "${(it.destination.remainingSeconds.toLong() + 59) / 60}분" }
                ?: if (vehicle.guidanceRefreshing) "확인 중" else "확인 필요",
                Modifier.weight(1f))
        }
    }
    if (guidance != null) {
        val remaining = plan.visits.drop(state.progress.completedVisits)
            .sumOf { it.visit.stayMinutes + it.visit.walkingMinutesOneWay * 2 }
        VehicleCaption("차량 내비의 현재 안내 기준 · 체류·도보 ${remaining}분 별도")
    } else {
        VehicleError(when (val result = vehicle.guidanceResult) {
            is GuidanceEstimateResult.Failed -> result.reason
            is GuidanceEstimateResult.Success -> "남은 시간 응답이 오래됐어요. 차량 내비에서 안내 시간을 확인해주세요"
            else -> if (vehicle.guidanceRefreshing) "차량 내비의 남은 시간을 확인하고 있어요"
                else "차량 내비에서 현재 안내 시간을 확인해주세요"
        })
        if (vehicle.guidanceResult is GuidanceEstimateResult.Failed) {
            OutlinedButton(onClick = actions.onResumeNavigation,
                enabled = canEdit && vehicle.connection == NavigationConnection.Ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                Text("남은 순서로 다시 안내", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            VehicleCaption("남은 이동 순서")
            plan.visits.drop(state.progress.completedVisits).forEachIndexed { index, visit ->
                VehicleStopLine("${index + 1}", visit.visit.place, visitLabel(visit.visit.kind), null)
            }
            VehicleStopLine("집", state.draft.home, "마지막 목적지", null)
        }
    }
}

@Composable
private fun VehicleComplete(state: HomeUiState) {
    val plan = state.selectedPlan
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("✓ 볼일을 마치고 집으로", fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
            plan?.visits?.forEach { visit ->
                Text("✓ ${visit.visit.place}", fontSize = 30.sp, lineHeight = 40.sp)
            }
            Text("✓ ${state.draft.home} 도착", fontSize = 30.sp, lineHeight = 40.sp)
        }
    }
}

@Composable
private fun VehicleNavigationStatus(vehicle: VehicleNavigationUiState, actions: JourneyActions, canEdit: Boolean) {
    val busy = vehicle.reading || vehicle.sending || vehicle.planning
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VehicleCaption("차량 내비")
                    Text(when (val connection = vehicle.connection) {
                        NavigationConnection.Unsupported -> "이 기기에서는 차량 내비에 연결할 수 없어요"
                        NavigationConnection.Connecting -> "연결하고 있어요"
                        NavigationConnection.Ready -> when {
                            vehicle.sending -> "경유 순서를 확인하고 있어요"
                            vehicle.reading -> "차량의 안내 상태를 확인하고 있어요"
                            vehicle.snapshot?.guidanceActive == true ->
                                "안내 중 · ${vehicle.snapshot.destination?.name ?: "목적지 확인 필요"}"
                            vehicle.snapshot != null -> "현재 길안내 중이 아니에요"
                            else -> "연결됐어요"
                        }
                        is NavigationConnection.Failed -> connection.reason
                    }, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
                }
                if (vehicle.connection == NavigationConnection.Ready) {
                    OutlinedButton(onClick = { if (!busy) actions.onReadNavigation() }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 80.dp).widthIn(min = 200.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("내비 확인", fontSize = 26.sp)
                    }
                } else if (vehicle.connection is NavigationConnection.Failed) {
                    OutlinedButton(onClick = { if (canEdit) actions.onReconnectNavigation() }, enabled = canEdit,
                        modifier = Modifier.heightIn(min = 80.dp).widthIn(min = 200.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("다시 연결", fontSize = 26.sp)
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vehicle.message?.takeUnless { vehicle.error }?.let { message ->
                Text(message, fontSize = 26.sp, lineHeight = 34.sp,
                    color = if (vehicle.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VehicleFooter(state: HomeUiState, actions: JourneyActions, vehicle: VehicleNavigationUiState, canEdit: Boolean) {
    val stage = state.progress.stage
    val plan = state.selectedPlan
    val anyValid = state.plans.any { it.isValid }
    val next = plan?.visits?.getOrNull(state.progress.completedVisits)
    val needsPlaces = vehicle.connection != NavigationConnection.Unsupported &&
        plan?.let { state.draft.navigationPlan(it) } == null
    val allPlacesChosen = state.draft.navigationPoint(NavigationPlaceRole.HOME) != null &&
        (!state.draft.pickup.enabled || state.draft.navigationPoint(NavigationPlaceRole.PICKUP) != null) &&
        (!state.draft.groceries.enabled || state.draft.navigationPoint(NavigationPlaceRole.GROCERIES) != null)
    val canRoad = vehicle.roadComparisonAvailable && vehicle.connection == NavigationConnection.Ready && allPlacesChosen
    val title = when (stage) {
        JourneyStage.INPUT -> when {
            vehicle.planning -> "경로 확인 중"
            !allPlacesChosen -> "집과 들를 곳을 선택하세요"
            !canRoad -> "내비 연결을 확인하세요"
            else -> "지금 출발 · 경로 비교"
        }
        JourneyStage.COMPARE -> if (anyValid) "이 순서로 갈게요" else "조건 바꾸기"
        JourneyStage.REVIEW -> when {
            vehicle.sending -> "차량 내비 확인 중"
            needsPlaces -> "장소를 다시 선택"
            vehicle.connection == NavigationConnection.Unsupported -> "계획 기록 시작"
            else -> "차량 내비로 시작"
        }
        JourneyStage.ACTIVE -> next?.let { "${visitLabel(it.visit.kind)} 완료" } ?: "집 도착 · 마무리"
        JourneyStage.COMPLETE -> "새 계획 만들기"
    }
    val enabled = canEdit && when (stage) {
        JourneyStage.INPUT -> canRoad && (state.draft.pickup.enabled || state.draft.groceries.enabled)
        JourneyStage.COMPARE -> !anyValid || plan?.isValid == true
        JourneyStage.REVIEW -> plan?.isValid == true && (needsPlaces || vehicle.connection == NavigationConnection.Ready ||
            vehicle.connection == NavigationConnection.Unsupported)
        JourneyStage.ACTIVE, JourneyStage.COMPLETE -> true
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                when (stage) {
                    JourneyStage.COMPARE, JourneyStage.REVIEW -> OutlinedButton(
                        onClick = { if (canEdit) { if (stage == JourneyStage.COMPARE) actions.onEdit() else actions.onBack() } },
                        enabled = canEdit, modifier = Modifier.heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp),
                    ) { Text(if (stage == JourneyStage.COMPARE) "선택 바꾸기" else "다시 비교", fontSize = 26.sp) }
                    JourneyStage.ACTIVE -> TextButton(onClick = { if (canEdit) actions.onCancel() }, enabled = canEdit,
                        modifier = Modifier.heightIn(min = 88.dp)) { Text("계획 중단", fontSize = 26.sp) }
                    JourneyStage.INPUT, JourneyStage.COMPLETE -> Unit
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (!canEdit && !vehicle.reading && !vehicle.sending && !vehicle.planning) "정차 후 사용할 수 있어요" else when (stage) {
                        JourneyStage.INPUT -> "들를 곳만 고르면 돼요"
                        JourneyStage.COMPARE -> "집까지의 전체 이동을 비교했어요"
                        JourneyStage.REVIEW -> "선택한 순서를 차량 내비로 전달해요"
                        JourneyStage.ACTIVE -> "완료 확인은 정차 후에 해주세요"
                        JourneyStage.COMPLETE -> "다음 귀가도 가는 김에"
                    }, fontSize = 24.sp, lineHeight = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = {
                    if (enabled) when (stage) {
                        JourneyStage.INPUT -> actions.onCompare()
                        JourneyStage.COMPARE -> if (anyValid) actions.onReview() else actions.onEdit()
                        JourneyStage.REVIEW -> if (needsPlaces) actions.onEdit() else actions.onStart()
                        JourneyStage.ACTIVE -> if (next == null) actions.onFinish() else actions.onCompleteVisit(next.visit.kind)
                        JourneyStage.COMPLETE -> actions.onNewJourney()
                    }
                }, enabled = enabled, modifier = Modifier.widthIn(min = 420.dp).heightIn(min = 96.dp),
                    shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 20.dp)) {
                    Text(title, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun VehicleChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 80.dp),
        shape = RoundedCornerShape(18.dp), border = BorderStroke(if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
        Text(label, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun VehicleMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VehicleCaption(label)
        Text(value, fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun VehicleStopLine(marker: String, place: String, description: String, time: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(marker, Modifier.widthIn(min = 64.dp), fontSize = 30.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(place, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            VehicleCaption(description)
        }
        if (time != null) Text(time, fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VehiclePlanIssues(plan: ErrandJourneyPlan) {
    plan.issues.forEach { issue ->
        val place = plan.visits.firstOrNull { it.visit.kind == issue.visit }?.visit?.place
        VehicleError(when (issue.kind) {
            PlanIssueKind.OVER_BUDGET -> "허용한 추가시간보다 ${issue.minutes}분 더 걸려요"
            PlanIssueKind.NOT_READY -> "${place ?: "픽업 장소"}의 수령 준비를 확인해주세요"
            PlanIssueKind.DEADLINE -> "${place ?: "방문 장소"} 마감에 ${issue.minutes}분 늦어요"
        })
    }
}

@Composable
private fun VehicleEstimateSource(state: HomeUiState) {
    val routed = state.routing
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (routed != null && state.routingMatchesDraft) {
            VehicleCaption("OSM 도로망 예상 · 실시간 교통 미반영")
            VehicleCaption("체류와 왕복 도보는 입력한 시간 기준 · 구간별 이동시간은 분 단위 올림")
            VehicleCaption("© OpenStreetMap contributors · ODbL · openstreetmap.org/copyright")
        } else {
            VehicleCaption("입력한 이동·도보·체류시간 기준 · 실제 교통상황에 따라 달라져요")
        }
        val direct = state.directHomeMinutes
        if (direct != null) VehicleCaption("곧바로 집까지 ${direct}분 · ${state.draft.departure} 출발 기준")
    }
}

@Composable
private fun VehicleError(message: String) {
    Text(message, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.error)
}

@Composable
private fun VehicleCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 22.sp, lineHeight = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun vehicleTitle(stage: JourneyStage): String = when (stage) {
    JourneyStage.INPUT -> "가는 김에 들렀다 가요"
    JourneyStage.COMPARE -> "어떤 순서로 갈까요?"
    JourneyStage.REVIEW -> "이 순서로 출발할게요"
    JourneyStage.ACTIVE -> "선택한 순서대로 하나씩"
    JourneyStage.COMPLETE -> "오늘 할 일을 마쳤어요"
}

private fun vehicleStage(stage: JourneyStage): String = when (stage) {
    JourneyStage.INPUT -> "1 · 들를 곳 선택"
    JourneyStage.COMPARE -> "2 · 귀가 계획 비교"
    JourneyStage.REVIEW -> "3 · 차량 내비로 출발"
    JourneyStage.ACTIVE -> "4 · 볼일과 귀가"
    JourneyStage.COMPLETE -> "귀가 완료"
}
