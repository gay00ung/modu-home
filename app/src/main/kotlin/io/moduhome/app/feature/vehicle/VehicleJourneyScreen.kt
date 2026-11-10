package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.GuidanceEstimateResult
import io.moduhome.app.core.planning.ErrandJourneyPlan
import io.moduhome.app.core.planning.DepartureClock
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.PlanIssueKind
import io.moduhome.app.core.planning.ScheduledVisit
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.planning.formatJourneyMinute
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyActions
import io.moduhome.app.feature.home.NavigationPlaceRole
import io.moduhome.app.feature.home.VehicleNavigationUiState
import io.moduhome.app.feature.home.VisitDraft
import io.moduhome.app.feature.home.planTitle
import io.moduhome.app.feature.home.visitLabel
import java.time.format.DateTimeFormatter

/** The app owns the journey and restriction state. This surface only renders it and emits intents. */
@Composable
internal fun VehicleJourneyScreen(
    state: HomeUiState,
    actions: JourneyActions,
    vehicle: VehicleNavigationUiState,
    editingAllowed: Boolean,
    restrictionMessage: String?,
    onOpenDetails: () -> Unit,
    onOpenShared: () -> Unit,
    onOpenPhoneConnection: () -> Unit,
    onVoice: () -> Unit,
    voiceAvailable: Boolean,
    modifier: Modifier = Modifier,
    voiceUnavailableMessage: String? = null,
    toolbarActions: (@Composable (Boolean) -> Unit)? = null,
) {
    val stage = state.progress.stage
    val busy = vehicle.sending || vehicle.reading || vehicle.planning
    val canEdit = editingAllowed && !busy
    val compareWithRoute = stage == JourneyStage.COMPARE && state.selectedPlan?.isValid == true
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 40.dp,
                vertical = if (compareWithRoute && !busy && !vehicle.error) 0.dp else 20.dp),
                contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = VehiclePageWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!compareWithRoute && stage != JourneyStage.REVIEW) VehicleToolbar(title = vehicleTitle(stage)) {
                        if (stage == JourneyStage.ACTIVE) toolbarActions?.invoke(canEdit)
                        if (stage == JourneyStage.INPUT || stage == JourneyStage.COMPLETE) {
                            OutlinedButton(onClick = { if (canEdit) onOpenShared() }, enabled = canEdit,
                                modifier = Modifier.heightIn(min = 80.dp), shape = MaterialTheme.shapes.medium) {
                                Text("함께 귀가", fontSize = 28.sp)
                            }
                        }
                        if (stage == JourneyStage.INPUT) {
                            OutlinedButton(onClick = { if (canEdit) onOpenPhoneConnection() }, enabled = canEdit,
                                modifier = Modifier.heightIn(min = 80.dp), shape = MaterialTheme.shapes.medium) {
                                Text("휴대폰 연결", fontSize = 28.sp)
                            }
                            OutlinedButton(onClick = { if (canEdit) actions.onOpenProposals() }, enabled = canEdit,
                                modifier = Modifier.heightIn(min = 80.dp), shape = MaterialTheme.shapes.medium) {
                                Text("동승자 볼일", fontSize = 28.sp)
                            }
                            if (voiceAvailable) {
                                OutlinedButton(onClick = { if (canEdit) onVoice() }, enabled = canEdit,
                                    modifier = Modifier.heightIn(min = 80.dp), shape = MaterialTheme.shapes.medium) {
                                    Text("말로 선택", fontSize = 28.sp)
                                }
                            }
                            TextButton(onClick = { if (canEdit) onOpenDetails() }, enabled = canEdit,
                                modifier = Modifier.heightIn(min = 80.dp)) {
                                Text("상세 조건", fontSize = 28.sp)
                            }
                        }
                    }
                    if (!editingAllowed || restrictionMessage != null) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                            Text(restrictionMessage ?: "정차 후 계획을 선택하거나 바꿀 수 있어요",
                                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                                fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (vehicle.error && vehicle.message != null) VehicleError(vehicle.message)
                    if (canEdit && stage == JourneyStage.INPUT && voiceUnavailableMessage != null) {
                        VehicleError(voiceUnavailableMessage)
                    }
                }
            }
        },
        bottomBar = { VehicleFooter(state, actions, vehicle, canEdit) },
    ) { padding ->
        key(stage) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (compareWithRoute || stage == JourneyStage.REVIEW) Arrangement.Center else Arrangement.Top) {
                Column(Modifier.widthIn(max = VehiclePageWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    when (stage) {
                        JourneyStage.INPUT -> VehicleInput(state, actions, vehicle, canEdit)
                        JourneyStage.COMPARE -> VehicleComparison(state, actions, canEdit, vehicle.roadComparisonAvailable)
                        JourneyStage.REVIEW -> {
                            VehicleToolbar(title = vehicleTitle(stage)) { toolbarActions?.invoke(canEdit) }
                            VehicleReview(state)
                        }
                        JourneyStage.ACTIVE -> VehicleActive(state, vehicle, actions, canEdit)
                        JourneyStage.COMPLETE -> VehicleComplete(state)
                    }
                    if (stage == JourneyStage.INPUT || busy || vehicle.connection != NavigationConnection.Ready) {
                        VehicleNavigationStatus(vehicle, actions, canEdit, stage)
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleInput(state: HomeUiState, actions: JourneyActions, vehicle: VehicleNavigationUiState, canEdit: Boolean) {
    val draft = state.draft
    val canChoosePlace = canEdit && vehicle.connection == NavigationConnection.Ready
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
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
                shape = MaterialTheme.shapes.medium) {
                Text(if (draft.navigationPoint(NavigationPlaceRole.HOME) == null) "집 선택" else "집 변경", fontSize = 28.sp)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 1000.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.1f
        val pickupCard: @Composable (Modifier) -> Unit = { cardModifier -> VehicleVisitCard(
            title = "픽업", visit = draft.pickup,
            placeName = draft.navigationPoint(NavigationPlaceRole.PICKUP)?.name,
            canEdit = canEdit, canChoosePlace = canChoosePlace,
            onChange = { actions.onDraftChange(draft.copy(pickup = it)) },
            onPlace = { actions.onChooseNavigationPlace(NavigationPlaceRole.PICKUP) },
            pickup = true, modifier = cardModifier,
            dateEpochDay = draft.dateEpochDay,
        ) }
        val groceriesCard: @Composable (Modifier) -> Unit = { cardModifier -> VehicleVisitCard(
            title = "장보기", visit = draft.groceries,
            placeName = draft.navigationPoint(NavigationPlaceRole.GROCERIES)?.name,
            canEdit = canEdit, canChoosePlace = canChoosePlace,
            onChange = { actions.onDraftChange(draft.copy(groceries = it)) },
            onPlace = { actions.onChooseNavigationPlace(NavigationPlaceRole.GROCERIES) },
            pickup = false, modifier = cardModifier,
            dateEpochDay = draft.dateEpochDay,
        ) }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                pickupCard(Modifier.fillMaxWidth())
                groceriesCard(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                pickupCard(Modifier.weight(1f).fillMaxHeight())
                groceriesCard(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("더 걸려도 괜찮은 시간", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            VehicleCaption("집 직행보다 최대 ${draft.budget}분 더")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(10, 20, 30, 60).forEach { minutes ->
                    VehicleChoice("${minutes}분", draft.budget == minutes, canEdit,
                        onClick = { if (canEdit) actions.onDraftChange(draft.copy(budget = minutes)) },
                        modifier = Modifier.widthIn(min = 132.dp))
                }
            }
        }
    }
    if (state.showErrors) {
        state.validation.errors.values.distinct().take(3).forEach { message -> VehicleError(message) }
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
    dateEpochDay: Long,
    modifier: Modifier = Modifier,
) {
    val enabled = canEdit && visit.enabled
    Surface(modifier, color = if (visit.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, Modifier.weight(1f), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                VehicleCaption(if (visit.enabled) "들를게요" else "다음에")
                Switch(checked = visit.enabled, onCheckedChange = { if (canEdit) onChange(visit.copy(enabled = it)) },
                    enabled = canEdit, modifier = Modifier.width(100.dp).heightIn(min = 80.dp))
            }
            OutlinedButton(onClick = { if (enabled && canChoosePlace) onPlace() }, enabled = enabled && canChoosePlace,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                Text(placeName ?: "장소 선택", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (visit.enabled) {
                val stay = visit.stay.toIntOrNull()?.takeIf { it in 0..180 }
                val walk = visit.walking.toIntOrNull()?.takeIf { it in 0..30 }
                if (stay !in listOf(5, 10, 15) || walk != 0) {
                    VehicleCaption(if (stay == null || walk == null) "체류·도보 조건을 확인해주세요"
                        else "체류 ${stay}분 · 편도 도보 ${walk}분")
                }
                if (visit.deadline.isNotBlank()) {
                    val date = DepartureClock.deadlineDate(dateEpochDay, visit.deadlineNextDay)
                    VehicleCaption(if (date == null) "마감 날짜를 확인해주세요"
                        else "마감 ${date.format(DateTimeFormatter.ofPattern("M월 d일"))} ${visit.deadline}")
                }
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), shape = MaterialTheme.shapes.medium,
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
private fun VehicleComparison(state: HomeUiState, actions: JourneyActions, canEdit: Boolean, roadComparisonAvailable: Boolean) {
    val selected = state.selectedPlan?.takeIf { it.isValid }
    if (selected != null) {
        VehicleErrandOrderPanel(selected, state, confirming = false)
        state.plans.firstOrNull { it.isValid && it.id != selected.id }?.let { alternate ->
            val earlier = selected.homeArrivalMinute - alternate.homeArrivalMinute
            val change = when {
                earlier > 0 -> "집 ${earlier}분 먼저"
                earlier < 0 -> "집 ${-earlier}분 늦게"
                else -> "집 도착 시각 같음"
            }
            VehicleOrderSwapButton(
                detail = "${visitLabel(alternate.visits.first().visit.kind)} 먼저 · $change",
                enabled = canEdit,
                onClick = { actions.onSelect(alternate.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (state.draft.pickup.enabled && state.draft.groceries.enabled) {
        val options = state.alternatives?.takeIf { it.matches(state.draft, state.routing) }?.options.orEmpty()
        VehicleErrandAlternatives(state.draft.budget, options, canEdit && roadComparisonAvailable,
            actions.onCompareAlternatives, actions.onSelectAlternative)
    }
    state.additionalTimeSuggestion?.let {
        val title = if (state.draft.pickup.enabled && state.draft.groceries.enabled)
            "두 볼일을 모두 하면 한도를 넘어요" else "현재 시간 한도에 맞는 순서가 없어요"
        VehicleAdditionalTimeSuggestion(it, canEdit, actions.onUseAdditionalTime, title = title)
    }
    if (state.plans.isEmpty()) {
        VehicleError("비교할 계획이 없어요. 장소와 조건을 다시 확인해주세요")
        return
    }
    val plansToShow = if (selected == null) state.plans else state.plans.filterNot { it.isValid }
    val recommended = state.plans.firstOrNull { it.isValid }?.id
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 1000.dp && fontScale <= 1.1f && plansToShow.size > 1) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                plansToShow.forEach { plan ->
                    VehicleComparisonCard(plan, state.progress.selectedPlanId == plan.id, plan.id == recommended, canEdit,
                        onSelect = { actions.onSelect(plan.id) }, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                plansToShow.forEach { plan ->
                    VehicleComparisonCard(plan, state.progress.selectedPlanId == plan.id, plan.id == recommended, canEdit,
                        onSelect = { actions.onSelect(plan.id) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    VehicleEstimateSource(state)
}

@Composable
private fun VehicleComparisonCard(
    plan: ErrandJourneyPlan,
    selected: Boolean,
    recommended: Boolean,
    canEdit: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.selectable(selected, enabled = canEdit,
            role = Role.RadioButton, onClick = { if (canEdit) onSelect() }),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (selected) "✓" else "○", fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
                VehicleCaption(when {
                    recommended -> "도로망 기준 추천 · 교통 제외"
                    plan.isValid -> "도로망 기준으로 조건에 맞아요"
                    else -> "조건 확인 필요"
                })
            }
            Text(planTitle(plan), fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
            VehicleMetrics(listOf("교통 제외 도착" to formatJourneyMinute(plan.homeArrivalMinute),
                "교통 제외 추가" to "+${plan.additionalMinutes}분"))
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

@Composable
private fun VehicleReview(state: HomeUiState) {
    val plan = state.selectedPlan
    if (plan == null) {
        VehicleError("확정할 계획을 다시 선택해주세요")
        return
    }
    VehicleErrandOrderPanel(plan, state, confirming = true)
    plan.visits.forEach { scheduled ->
        deadlineNote(scheduled, state.draft.dateEpochDay)?.let { note ->
            VehicleCaption("${scheduled.visit.place} · $note")
        }
    }
    state.navigationWindow?.takeIf { it.destination != state.draft.navigationPoint(NavigationPlaceRole.HOME) }?.let { window ->
        VehicleCaption("${window.destination.name}까지 먼저 안내 · 볼일 완료 후 다음 목적지로")
    }
    VehiclePlanIssues(plan)
    VehicleEstimateSource(state)
}

@Composable
private fun VehicleErrandOrderPanel(plan: ErrandJourneyPlan, state: HomeUiState, confirming: Boolean) {
    val placeLines = if (confirming) Int.MAX_VALUE else 2
    val extraTime = "직행 +${plan.additionalMinutes}분"
    val home = state.draft.navigationPoint(NavigationPlaceRole.HOME)?.name ?: state.draft.home
    val stops = plan.visits.mapIndexed { index, scheduled ->
        OrderArrival(index + 1, scheduled.visit.place, visitLabel(scheduled.visit.kind), scheduled.arrivalMinute, placeLines)
    }
    val last = OrderArrival(null, if (confirming) home else "집 도착",
        if (confirming) "집 도착 · $extraTime" else extraTime, plan.homeArrivalMinute, placeLines)
    VehicleOrderPanel(
        arrivals = stops + last,
        title = if (confirming) null else "방문 순서",
        estimate = if (state.routingMatchesDraft) "실시간 교통 제외" else "입력한 시간",
        animate = !confirming && stops.isNotEmpty(),
    )
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
    val homeWindow = state.navigationWindow?.destination == state.draft.navigationPoint(NavigationPlaceRole.HOME)
    val nextGuided = guidance?.let { it.waypoints.firstOrNull() ?: it.destination }
    val completed = state.progress.completedVisits
    val nextRole = when (next?.visit?.kind) {
        VisitKind.PICKUP -> NavigationPlaceRole.PICKUP
        VisitKind.GROCERIES -> NavigationPlaceRole.GROCERIES
        null -> NavigationPlaceRole.HOME
    }
    val plannedPoint = state.draft.navigationPoint(nextRole)
    val guidanceMatchesPlan = nextGuided == null || nextGuided.point == plannedPoint
    VehicleNextStopPanel(
        place = nextGuided?.point?.name ?: next?.visit?.place ?: state.draft.home,
        label = when {
            !guidanceMatchesPlan -> "차량 내비가 안내하는 곳"
            next == null -> "마지막 목적지 · 집"
            nextGuided != null -> "다음 정차 · ${completed + 1}/${plan.visits.size}곳"
            else -> "계획상 다음 정차"
        },
        detail = next?.takeIf { guidanceMatchesPlan }?.let { "${visitLabel(it.visit.kind)} · 체류 ${it.visit.stayMinutes}분" },
        modifier = Modifier.fillMaxWidth(),
        progress = {
            VehicleProgressRail(plan.visits.size, completed, guidanceReady = guidance != null)
        },
    ) {
        if (guidance != null && nextGuided != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VehicleMetric("다음 장소까지", "${(nextGuided.remainingSeconds.toLong() + 59) / 60}분")
                if (guidance.destination != nextGuided) {
                    VehicleCaption("${if (homeWindow) "집" else "이번 구간 끝"}까지 ${(guidance.destination.remainingSeconds.toLong() + 59) / 60}분")
                }
            }
        } else {
            VehicleMetric("남은 주행", if (vehicle.guidanceRefreshing) "확인 중" else "내비에서 확인")
        }
    }
    if (guidance != null) {
        val remaining = plan.visits.drop(state.progress.completedVisits)
            .sumOf { it.visit.stayMinutes + it.visit.walkingMinutesOneWay * 2 }
        VehicleCaption("차량 내비의 현재 안내 기준 · 체류·도보 ${remaining}분 별도")
        if (!homeWindow) VehicleCaption("볼일 완료 후 다음 구간을 안내해요. 집까지의 전체 주행 시간은 아직 확인하지 않았어요")
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp), shape = MaterialTheme.shapes.medium) {
                Text("남은 순서로 다시 안내", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    VehicleRouteDetails(Modifier.fillMaxWidth()) {
        plan.visits.forEachIndexed { index, visit ->
            val visited = index < completed
            VehicleStopLine(if (visited) "✓" else "${index + 1}", visit.visit.place,
                if (visited) "${visitLabel(visit.visit.kind)} · 완료 확인" else visitLabel(visit.visit.kind), null)
        }
        VehicleStopLine("집", state.draft.home, "마지막 목적지", null)
        VehicleNavigationStatus(vehicle, actions, canEdit, JourneyStage.ACTIVE)
    }
}

@Composable
private fun VehicleComplete(state: HomeUiState) {
    VehicleCompletionPanel(state.draft.home, "볼일과 집 도착을 기록했어요", Modifier.fillMaxWidth())
    VehicleRouteDetails(Modifier.fillMaxWidth()) {
        state.selectedPlan?.visits?.forEach { visit ->
            VehicleStopLine("✓", visit.visit.place, "${visitLabel(visit.visit.kind)} 완료", null)
        }
        VehicleStopLine("✓", state.draft.home, "집 도착 확인", null)
    }
}

@Composable
private fun VehicleNavigationStatus(
    vehicle: VehicleNavigationUiState,
    actions: JourneyActions,
    canEdit: Boolean,
    stage: JourneyStage,
) {
    val busy = vehicle.reading || vehicle.sending || vehicle.planning
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
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
                        modifier = Modifier.heightIn(min = 80.dp).widthIn(min = 200.dp), shape = MaterialTheme.shapes.medium) {
                        Text("내비 확인", fontSize = 28.sp)
                    }
                } else if (vehicle.connection is NavigationConnection.Failed) {
                    OutlinedButton(onClick = { if (canEdit) actions.onReconnectNavigation() }, enabled = canEdit,
                        modifier = Modifier.heightIn(min = 80.dp).widthIn(min = 200.dp), shape = MaterialTheme.shapes.medium) {
                        Text("다시 연결", fontSize = 28.sp)
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (stage) {
                JourneyStage.INPUT, JourneyStage.COMPARE, JourneyStage.REVIEW -> {
                    vehicle.message?.takeUnless { vehicle.error }?.let { message ->
                        Text(message, fontSize = 28.sp, lineHeight = 38.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                JourneyStage.ACTIVE, JourneyStage.COMPLETE -> Unit
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
            else -> "방문 순서 비교"
        }
        JourneyStage.COMPARE -> if (anyValid) "순서 확인" else "조건 변경"
        JourneyStage.REVIEW -> when {
            vehicle.sending -> "차량 내비 확인 중"
            needsPlaces -> "장소를 다시 선택"
            vehicle.connection == NavigationConnection.Unsupported -> "계획 기록 시작"
            else -> "내비 시작"
        }
        JourneyStage.ACTIVE -> next?.let { "${visitLabel(it.visit.kind)} 완료" } ?: "집에 도착했어요"
        JourneyStage.COMPLETE -> "새 계획 만들기"
    }
    val enabled = canEdit && when (stage) {
        JourneyStage.INPUT -> canRoad && (state.draft.pickup.enabled || state.draft.groceries.enabled)
        JourneyStage.COMPARE -> !anyValid || plan?.isValid == true
        JourneyStage.REVIEW -> plan?.isValid == true && (needsPlaces || vehicle.connection == NavigationConnection.Ready ||
            vehicle.connection == NavigationConnection.Unsupported)
        JourneyStage.ACTIVE, JourneyStage.COMPLETE -> true
    }
    val secondaryAction: (@Composable () -> Unit)? = when (stage) {
        JourneyStage.COMPARE, JourneyStage.REVIEW -> ({
            OutlinedButton(
                onClick = { if (canEdit) { if (stage == JourneyStage.COMPARE) actions.onEdit() else actions.onBack() } },
                enabled = canEdit, modifier = Modifier.heightIn(min = 88.dp), shape = MaterialTheme.shapes.medium,
            ) { Text(if (stage == JourneyStage.COMPARE) "선택 바꾸기" else "다시 비교", fontSize = 28.sp) }
        })
        JourneyStage.ACTIVE -> ({
            TextButton(onClick = { if (canEdit) actions.onCancel() }, enabled = canEdit,
                modifier = Modifier.heightIn(min = 88.dp)) { Text("계획 중단", fontSize = 28.sp) }
        })
        JourneyStage.INPUT, JourneyStage.COMPLETE -> null
    }
    VehicleActionBar(
        caption = if (!canEdit && !vehicle.reading && !vehicle.sending && !vehicle.planning) "정차 후 사용할 수 있어요" else when (stage) {
            JourneyStage.INPUT, JourneyStage.REVIEW, JourneyStage.COMPLETE -> null
            JourneyStage.COMPARE -> if (anyValid && plan?.isValid != true) "확인할 순서를 선택해주세요" else null
            JourneyStage.ACTIVE -> "완료 확인은 정차 후에 해주세요"
        },
        actionLabel = title,
        actionEnabled = enabled,
        onAction = {
            when (stage) {
                JourneyStage.INPUT -> actions.onCompare()
                JourneyStage.COMPARE -> if (anyValid) actions.onReview() else actions.onEdit()
                JourneyStage.REVIEW -> if (needsPlaces) actions.onEdit() else actions.onStart()
                JourneyStage.ACTIVE -> if (next == null) actions.onFinish() else actions.onCompleteVisit(next.visit.kind)
                JourneyStage.COMPLETE -> actions.onNewJourney()
            }
        },
        secondaryAction = secondaryAction,
    )
}

@Composable
private fun VehicleChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.sizeIn(minWidth = 80.dp, minHeight = 80.dp),
        shape = MaterialTheme.shapes.medium, border = BorderStroke(if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
        Text(label, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun VehicleStopLine(marker: String, place: String, description: String, time: String?, note: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(marker, Modifier.widthIn(min = 64.dp), fontSize = 30.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(place, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            VehicleCaption(description)
            if (note != null) VehicleCaption(note)
        }
        if (time != null) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VehicleCaption("도착")
            Text(time, fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun deadlineNote(scheduled: ScheduledVisit, dateEpochDay: Long): String? {
    val slack = scheduled.deadlineSlackMinutes ?: return null
    val deadline = scheduled.visit.deadlineMinute ?: return null
    val date = DepartureClock.deadlineDate(dateEpochDay, deadline >= 1440)
        ?.format(DateTimeFormatter.ofPattern("M월 d일")) ?: "날짜 확인 필요"
    val service = when (scheduled.visit.kind) {
        VisitKind.PICKUP -> "수령"
        VisitKind.GROCERIES -> "장보기"
    }
    val margin = when {
        slack < 0 -> "${-slack}분 늦음"
        slack == 0 -> "마감과 같아요"
        else -> "${slack}분 여유"
    }
    return "교통 제외 $service 완료 ${formatJourneyMinute(scheduled.serviceFinishedMinute)}\n" +
        "마감 $date ${formatJourneyMinute(deadline % 1440)} · $margin"
}

@Composable
internal fun VehiclePlanIssues(plan: ErrandJourneyPlan) {
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
            VehicleCaption("체류와 왕복 도보 포함")
            val source = "© OpenStreetMap contributors · ODbL · openstreetmap.org/copyright"
            VehicleCaption(listOfNotNull(state.directHomeMinutes?.let { "집 직행 ${it}분" }, source).joinToString(" · "))
        } else {
            VehicleCaption("입력한 이동·도보·체류시간 기준 · 실제 교통상황에 따라 달라져요")
        }
    }
}

@Composable
private fun VehicleError(message: String) {
    Text(message, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.error)
}

@Composable
private fun VehicleCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 28.sp, lineHeight = 38.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun vehicleTitle(stage: JourneyStage): String = when (stage) {
    JourneyStage.INPUT -> "가는김에"
    JourneyStage.COMPARE -> "순서 비교"
    JourneyStage.REVIEW -> "이 순서로 출발할까요?"
    JourneyStage.ACTIVE -> "귀가 안내"
    JourneyStage.COMPLETE -> "귀가 완료"
}
