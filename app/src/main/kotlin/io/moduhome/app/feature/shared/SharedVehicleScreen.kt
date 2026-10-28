package io.moduhome.app.feature.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.planning.formatJourneyMinute
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.SharedJourneyPlan
import io.moduhome.app.core.shared.SharedPlanIssue
import io.moduhome.app.core.shared.SharedScheduledStop
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.core.vehicle.restrictContent
import io.moduhome.app.feature.home.VehicleNavigationUiState

/** Pure vehicle presentation. Place lookup, user confirmations, routing and persistence belong to the app. */
@Composable
internal fun SharedVehicleScreen(
    state: SharedVehicleUiState,
    actions: SharedVehicleActions,
    vehicle: VehicleNavigationUiState,
    interaction: DrivingInteraction,
    modifier: Modifier = Modifier,
) {
    if (!interaction.editingAllowed) {
        SharedRestrictedScreen(state, interaction, modifier)
        return
    }
    val busy = vehicle.planning || vehicle.sending || vehicle.reading
    val canEdit = !busy
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                Modifier.statusBarsPadding().padding(horizontal = 40.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(sharedTitle(state.stage), Modifier.weight(1f), fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
                    if (state.stage == JourneyStage.INPUT) SharedAction("가는 김에", canEdit, actions.onLeave)
                }
                SharedCaption(when (state.stage) {
                    JourneyStage.INPUT -> "동승자를 집 앞에 내려주고, 가는 김에 볼일까지"
                    JourneyStage.COMPARE -> "운전자와 동승자의 도착을 함께 비교해요"
                    JourneyStage.REVIEW -> "집 앞 하차와 볼일의 전체 순서를 확인해주세요"
                    JourneyStage.ACTIVE -> "하차와 완료 확인은 정차한 뒤에 해주세요"
                    JourneyStage.COMPLETE -> "동승자 하차와 집 도착을 기록했어요"
                })
                val errors = (if (state.showErrors) state.errors else emptyList()) +
                    listOfNotNull((vehicle.connection as? NavigationConnection.Failed)?.reason,
                        vehicle.message?.takeIf { vehicle.error })
                errors.distinct().take(2).forEach { SharedError(it) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = { SharedFooter(state, actions, vehicle, canEdit) },
    ) { padding ->
        key(state.stage) {
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when (state.stage) {
                    JourneyStage.INPUT -> SharedInput(state.draft, actions, vehicle, canEdit)
                    JourneyStage.COMPARE -> SharedComparison(state, actions, canEdit)
                    JourneyStage.REVIEW -> SharedReview(state)
                    JourneyStage.ACTIVE -> SharedActive(state, actions, vehicle, canEdit)
                    JourneyStage.COMPLETE -> SharedComplete(state)
                }
                SharedNavigationStatus(state, vehicle, actions, canEdit)
            }
        }
    }
}

@Composable
private fun SharedInput(
    draft: SharedJourneyDraft,
    actions: SharedVehicleActions,
    vehicle: VehicleNavigationUiState,
    canEdit: Boolean,
) {
    val canChoose = canEdit && vehicle.connection == NavigationConnection.Ready
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SharedCaption("마지막 목적지 · 운전자 집")
                Text(draft.driverHome?.name ?: "어디로 돌아갈까요?", fontSize = 32.sp, lineHeight = 42.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SharedAction(if (draft.driverHome == null) "집 선택" else "집 변경", canChoose, actions.onChooseDriverHome)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("함께 타는 동승자", Modifier.weight(1f), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        listOf(1, 2).forEach { count ->
            SharedChoice("${count}명", draft.passengers.size == count, canEdit,
                onClick = { if (canEdit) actions.onPassengerCountChange(count) }, modifier = Modifier.widthIn(min = 150.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 1_050.dp && draft.passengers.size == 2) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                draft.passengers.forEach { passenger ->
                    key(passenger.id) {
                        SharedPassengerCard(passenger, actions, canEdit, canChoose,
                            Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                draft.passengers.forEach { passenger ->
                    key(passenger.id) { SharedPassengerCard(passenger, actions, canEdit, canChoose, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("가는 김에 들를 곳", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SharedChoice("볼일 없음", draft.errand == null, canEdit,
                    onClick = { if (canEdit) actions.onErrandChoice(null) }, modifier = Modifier.weight(1f))
                SharedChoice("픽업", draft.errand?.kind == VisitKind.PICKUP, canEdit,
                    onClick = { if (canEdit) actions.onErrandChoice(VisitKind.PICKUP) }, modifier = Modifier.weight(1f))
                SharedChoice("장보기", draft.errand?.kind == VisitKind.GROCERIES, canEdit,
                    onClick = { if (canEdit) actions.onErrandChoice(VisitKind.GROCERIES) }, modifier = Modifier.weight(1f))
            }
            draft.errand?.let { errand ->
                SharedAction(errand.place?.name ?: "볼일 장소 선택", canChoose, actions.onChooseErrandPlace,
                    Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("머무는 시간", Modifier.weight(1f), fontSize = 28.sp)
                    listOf(5, 10, 15).forEach { minutes ->
                        SharedChoice("${minutes}분", errand.stayMinutes == minutes, canEdit,
                            onClick = { if (canEdit) actions.onDraftChange(draft.copy(errand = errand.copy(stayMinutes = minutes))) },
                            modifier = Modifier.widthIn(min = 140.dp))
                    }
                }
                if (errand.kind == VisitKind.PICKUP) {
                    SharedChoice(if (errand.ready) "✓ 수령 준비됐어요" else "수령 준비 확인", errand.ready, canEdit,
                        onClick = { if (canEdit) actions.onDraftChange(draft.copy(errand = errand.copy(ready = !errand.ready))) },
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("더 걸려도 괜찮은 시간", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                SharedCaption("운전자 집 직행보다 최대 ${draft.allowedAdditionalMinutes}분 더")
            }
            listOf(10, 20, 30, 60).forEach { minutes ->
                SharedChoice("${minutes}분", draft.allowedAdditionalMinutes == minutes, canEdit,
                    onClick = { if (canEdit) actions.onDraftChange(draft.copy(allowedAdditionalMinutes = minutes)) },
                    modifier = Modifier.widthIn(min = 132.dp))
            }
        }
    }
    SharedCaption("현재 차량 위치에서 출발해요 · 동승자는 직접 확인한 집 앞 장소에 내려요")
}

@Composable
private fun SharedPassengerCard(
    passenger: SharedPassengerDraft,
    actions: SharedVehicleActions,
    canEdit: Boolean,
    canChoose: Boolean,
    modifier: Modifier = Modifier,
) {
    val confirmed = dropoffConfirmed(passenger)
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("${passenger.name} · 집 앞 하차", fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
            SharedAction(passenger.dropoff?.name ?: "하차 장소 선택", canChoose,
                onClick = { actions.onChoosePassengerDropoff(passenger.id) }, modifier = Modifier.fillMaxWidth())
            SharedCaption("내린 뒤 다른 이동이 없는 장소를 직접 확인해주세요")
            SharedChoice(
                label = if (confirmed) "✓ 집 앞에 안전하게 정차하고 바로 내릴 수 있어요" else "집 앞에 안전하게 정차하고 바로 내릴 수 있어요",
                selected = confirmed,
                enabled = canEdit && passenger.dropoff != null,
                onClick = { if (canEdit && passenger.dropoff != null) actions.onConfirmDropoff(passenger.id, !confirmed) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SharedComparison(state: SharedVehicleUiState, actions: SharedVehicleActions, canEdit: Boolean) {
    if (state.plans.isEmpty()) {
        SharedError("비교할 경로가 없어요. 장소와 하차 조건을 다시 확인해주세요")
        return
    }
    val pages = (state.plans.size + 2) / 3
    val page = state.planPage.coerceIn(0, pages - 1)
    val visible = state.plans.drop(page * 3).take(3)
    val recommended = state.plans.firstOrNull { it.isValid }?.id
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 1_050.dp && visible.size > 1) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                visible.forEach { plan ->
                    key(plan.id) {
                        SharedPlanCard(plan, plan.id == state.selectedPlanId, plan.id == recommended, canEdit,
                            onSelect = { actions.onSelectPlan(plan.id) }, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                visible.forEach { plan ->
                    key(plan.id) {
                        SharedPlanCard(plan, plan.id == state.selectedPlanId, plan.id == recommended, canEdit,
                            onSelect = { actions.onSelectPlan(plan.id) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (pages > 1) {
        SharedAction(if (page == 0) "다른 계획 보기 · ${state.plans.size - visible.size}개" else "앞의 계획 보기 · ${minOf(3, state.plans.size)}개",
            canEdit, onClick = { actions.onPlanPageChange((page + 1) % pages) }, modifier = Modifier.fillMaxWidth())
    }
    state.selectedPlan?.let { SharedCaption("선택한 순서 · ${orderLabel(it)}") }
    SharedEstimateNote(state)
}

@Composable
private fun SharedPlanCard(
    plan: SharedJourneyPlan,
    selected: Boolean,
    recommended: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
            onClick = { if (enabled) onSelect() }),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SharedCaption(when {
                selected && recommended -> "✓ 선택한 추천 계획"
                selected -> "✓ 선택한 계획"
                recommended -> "전원 이동시간이 짧은 계획"
                plan.isValid -> "조건에 맞는 계획"
                else -> "조건 확인 필요"
            })
            Text(orderLabel(plan), fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            SharedMetric("교통 제외 · 운전자 집 도착", clock(plan.driverHomeArrivalMinute))
            Text(plan.driverAdditionalMinutes?.let { "집 직행보다 +${it}분" } ?: "추가시간 확인 필요", fontSize = 28.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            plan.passengerArrivals.forEach { arrival ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(arrival.passengerName, Modifier.weight(1f), fontSize = 26.sp, lineHeight = 36.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(clock(arrival.homeArrivalMinute), Modifier.widthIn(max = 220.dp), fontSize = 32.sp, lineHeight = 42.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
            }
            plan.issues.take(2).forEach { SharedError(issueText(it, plan)) }
            if (plan.issues.size > 2) SharedCaption("그 밖에 ${plan.issues.size - 2}개 조건 확인")
            if (plan.isValid) SharedCaption(if (selected) "이 순서를 확인하고 출발해요" else "눌러서 이 순서 선택")
        }
    }
}

@Composable
private fun SharedReview(state: SharedVehicleUiState) {
    val plan = state.selectedPlan
    if (plan == null) {
        SharedError("확정할 계획을 다시 선택해주세요")
        return
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                SharedMetric("교통 제외 · 집 도착", clock(plan.driverHomeArrivalMinute), Modifier.weight(1f))
                SharedMetric("집 직행보다 추가", plan.driverAdditionalMinutes?.let { "+${it}분" } ?: "확인 필요", Modifier.weight(1f))
                SharedMetric("중간 정차", "${plan.stops.size}곳", Modifier.weight(1f))
            }
            HorizontalDivider()
            plan.stops.forEachIndexed { index, stop ->
                val arrivalMinute = when (stop) {
                    is SharedScheduledStop.Dropoff -> plan.passengerArrivals
                        .singleOrNull { it.passengerId == stop.passenger.id }?.homeArrivalMinute
                    is SharedScheduledStop.Errand -> stop.arrivalMinute
                }
                SharedStopLine("${index + 1}", stop.place.point.name, stopLabel(stop), clock(arrivalMinute))
            }
            SharedStopLine("집", state.draft.driverHome?.name ?: "운전자 집 확인 필요", "운전자의 마지막 목적지", clock(plan.driverHomeArrivalMinute))
        }
    }
    if (plan.stops.size == 3) SharedStagedGuidanceNote()
    plan.issues.forEach { SharedError(issueText(it, plan)) }
    SharedEstimateNote(state)
}

@Composable
private fun SharedActive(
    state: SharedVehicleUiState,
    actions: SharedVehicleActions,
    vehicle: VehicleNavigationUiState,
    canEdit: Boolean,
) {
    val plan = state.selectedPlan
    if (plan == null) {
        SharedError("저장된 전체 계획을 확인하지 못했어요. 차량 내비의 안내를 확인해주세요")
        return
    }
    val next = plan.stops.getOrNull(state.completedStops)
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.fillMaxWidth().padding(36.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SharedCaption(if (next == null) "중간 정차를 마쳤어요 · 이제 운전자 집으로" else "계획상 다음 정차")
                Text(next?.place?.point?.name ?: state.draft.driverHome?.name ?: "운전자 집 확인 필요",
                    fontSize = 48.sp, lineHeight = 62.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                next?.let { Text(stopLabel(it), fontSize = 30.sp, lineHeight = 40.sp) }
            }
            SharedMetric("정차 완료", "${state.completedStops}/${plan.stops.size}곳", Modifier.weight(1f))
        }
    }
    if (state.navigationPending && next != null) {
        SharedError("남은 순서가 아직 차량 내비에 반영되지 않았어요")
        if (state.canConfirmPassedStop) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    SharedCaption("차량 내비는 다음 목적지를 안내하고 있어요. 이 정차를 실제로 마쳤다면 직접 확인해주세요")
                    Text(next.place.point.name, fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
                    SharedAction(if (next is SharedScheduledStop.Dropoff) "이미 하차했나요?" else "이미 볼일을 마쳤나요?",
                        canEdit && vehicle.connection == NavigationConnection.Ready, actions.onConfirmPassedStop,
                        Modifier.fillMaxWidth())
                }
            }
        }
        SharedAction(if (vehicle.sending) "차량 내비 확인 중" else "남은 순서 다시 반영",
            canEdit && vehicle.connection == NavigationConnection.Ready, actions.onRetryNavigation, Modifier.fillMaxWidth())
    } else if (state.navigationPending) {
        SharedCaption("집 안내를 다시 시작하거나, 집에 도착했다면 귀가를 마칠 수 있어요")
        SharedAction("운전자 집으로 다시 안내", canEdit && vehicle.connection == NavigationConnection.Ready,
            actions.onRetryNavigation, Modifier.fillMaxWidth())
    } else if (plan.stops.size == 3 && state.completedStops == 0) {
        SharedStagedGuidanceNote()
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("함께 가는 전체 순서", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            plan.stops.forEachIndexed { index, stop ->
                SharedStopLine(if (index < state.completedStops) "✓" else "${index + 1}", stop.place.point.name,
                    if (index < state.completedStops) "${stopLabel(stop)} · 완료 확인" else stopLabel(stop), null)
            }
            SharedStopLine("집", state.draft.driverHome?.name ?: "운전자 집 확인 필요", "마지막 목적지", null)
        }
    }
    if (!state.navigationPending) {
        SharedAction("차량 내비에 남은 순서 다시 반영", canEdit && vehicle.connection == NavigationConnection.Ready,
            actions.onRetryNavigation, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SharedComplete(state: SharedVehicleUiState) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("모두 집으로", fontSize = 52.sp, lineHeight = 66.sp, fontWeight = FontWeight.Bold)
            state.selectedPlan?.stops?.forEach { stop ->
                SharedStopLine("✓", stop.place.point.name, "${stopLabel(stop)} · 완료 확인", null)
            }
            SharedStopLine("✓", state.draft.driverHome?.name ?: "운전자 집", "집 도착 확인", null)
        }
    }
    SharedEstimateNote(state)
}

@Composable
private fun SharedFooter(state: SharedVehicleUiState, actions: SharedVehicleActions, vehicle: VehicleNavigationUiState, canEdit: Boolean) {
    val plan = state.selectedPlan
    val next = plan?.stops?.getOrNull(state.completedStops)
    val errand = state.draft.errand
    val allPlacesChosen = state.draft.driverHome != null && state.draft.passengers.size in 1..2 &&
        state.draft.passengers.all { it.dropoff != null } && (errand == null || errand.place != null)
    val allDropoffsConfirmed = state.draft.passengers.all(::dropoffConfirmed)
    val connected = vehicle.connection == NavigationConnection.Ready
    val anyValid = state.plans.any { it.isValid }
    val label = when (state.stage) {
        JourneyStage.INPUT -> when {
            vehicle.planning -> "모두의 경로 확인 중"
            !allPlacesChosen -> "집과 하차 장소 선택"
            !allDropoffsConfirmed -> "하차 장소를 확인해주세요"
            !connected || !vehicle.roadComparisonAvailable -> "내비 연결을 확인해주세요"
            else -> "지금 출발 · 함께 비교"
        }
        JourneyStage.COMPARE -> if (anyValid) "이 순서로 갈게요" else "조건 바꾸기"
        JourneyStage.REVIEW -> if (vehicle.sending) "차량 내비 확인 중" else "차량 내비로 시작"
        JourneyStage.ACTIVE -> when {
            next == null && plan != null && state.completedStops == plan.stops.size -> "집에 도착했어요"
            state.navigationPending -> "내비 반영을 먼저 확인"
            next != null -> when (next) {
                is SharedScheduledStop.Dropoff -> "${next.passenger.name} 하차 완료"
                is SharedScheduledStop.Errand -> "${errandLabel(next.errand.visit.kind)} 완료"
            }
            else -> "완료한 정차를 확인해주세요"
        }
        JourneyStage.COMPLETE -> "새 함께 귀가 계획"
    }
    val enabled = canEdit && when (state.stage) {
        JourneyStage.INPUT -> allPlacesChosen && allDropoffsConfirmed && connected && vehicle.roadComparisonAvailable
        JourneyStage.COMPARE -> !anyValid || plan?.isValid == true
        JourneyStage.REVIEW -> plan?.isValid == true && connected
        JourneyStage.ACTIVE -> plan != null && state.completedStops in 0..plan.stops.size &&
            (!state.navigationPending || state.completedStops == plan.stops.size)
        JourneyStage.COMPLETE -> true
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                when (state.stage) {
                    JourneyStage.COMPARE, JourneyStage.REVIEW -> SharedAction(
                        if (state.stage == JourneyStage.COMPARE) "선택 바꾸기" else "다시 비교", canEdit,
                        onClick = { if (state.stage == JourneyStage.COMPARE) actions.onEdit() else actions.onBack() },
                    )
                    JourneyStage.ACTIVE -> TextButton(onClick = { if (canEdit) actions.onCancel() }, enabled = canEdit,
                        modifier = Modifier.heightIn(min = 88.dp)) { Text("계획 중단", fontSize = 26.sp) }
                    JourneyStage.INPUT, JourneyStage.COMPLETE -> Unit
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (state.stage) {
                        JourneyStage.INPUT -> "집 앞 하차 장소만 선택해주세요"
                        JourneyStage.COMPARE -> "선택한 전체 순서를 확인해요"
                        JourneyStage.REVIEW -> "운전자 집이 마지막 목적지예요"
                        JourneyStage.ACTIVE -> "완료 확인은 정차한 뒤에"
                        JourneyStage.COMPLETE -> "다음 귀가도 함께 편하게"
                    }, fontSize = 24.sp, lineHeight = 34.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = {
                    if (enabled) when (state.stage) {
                        JourneyStage.INPUT -> actions.onCompare()
                        JourneyStage.COMPARE -> if (anyValid) actions.onReview() else actions.onEdit()
                        JourneyStage.REVIEW -> actions.onStart()
                        JourneyStage.ACTIVE -> if (next == null) actions.onFinish() else actions.onCompleteNextStop()
                        JourneyStage.COMPLETE -> actions.onNewJourney()
                    }
                }, enabled = enabled, modifier = Modifier.widthIn(min = 420.dp).heightIn(min = 96.dp),
                    shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp)) {
                    Text(label, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SharedStagedGuidanceNote() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
        Text("중간 정차가 3곳이에요. 첫 정차를 완료하면 나머지 경유지를 차량 내비에 반영해요",
            Modifier.fillMaxWidth().padding(24.dp), fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SharedEstimateNote(state: SharedVehicleUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SharedCaption(state.estimateLabel ?: "이동시간 산출 기준을 확인해주세요")
        state.directHomeMinutes?.let { SharedCaption("운전자 집 직행 ${it}분 · 정차와 체류시간 별도") }
        SharedCaption("동승자는 선택한 집 앞에 도착하는 기준이에요")
        SharedCaption("© OpenStreetMap contributors · ODbL · openstreetmap.org/copyright")
    }
}

@Composable
private fun SharedNavigationStatus(
    state: SharedVehicleUiState,
    vehicle: VehicleNavigationUiState,
    actions: SharedVehicleActions,
    canEdit: Boolean,
) {
    val status = when (val connection = vehicle.connection) {
        NavigationConnection.Unsupported -> "Pleos 차량에서 내비에 연결할 수 있어요"
        NavigationConnection.Connecting -> "차량 내비에 연결하고 있어요"
        is NavigationConnection.Failed -> connection.reason
        NavigationConnection.Ready -> when {
            vehicle.sending -> "경유 순서를 차량 내비에서 확인하고 있어요"
            state.navigationPending -> "남은 경유 순서 반영 대기"
            else -> "차량 내비 연결됨"
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(status, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium)
            vehicle.message?.takeUnless { vehicle.error }?.let { SharedCaption(it) }
            if (vehicle.connection is NavigationConnection.Failed) {
                SharedAction("차량 내비 다시 연결", canEdit, actions.onReconnect, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SharedRestrictedScreen(state: SharedVehicleUiState, interaction: DrivingInteraction, modifier: Modifier) {
    val next = state.selectedPlan?.stops?.getOrNull(state.completedStops)?.place?.point?.name ?: state.draft.driverHome?.name
    val messages = if (interaction is DrivingInteraction.Unknown) listOf(interaction.reason) else if (state.stage == JourneyStage.ACTIVE) {
        listOfNotNull("차량 내비를 따라가세요", next, "계획 변경과 완료 확인은 정차 후에")
    } else listOf("정차 후 함께 귀가 계획을 선택해주세요", "선택한 장소와 계획은 보관하고 있어요")
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(56.dp), verticalArrangement = Arrangement.Center) {
            interaction.restrictContent(messages).forEachIndexed { index, text ->
                Text(text, modifier = Modifier.padding(vertical = 28.dp), fontSize = if (index == 1) 52.sp else 36.sp,
                    lineHeight = if (index == 1) 66.sp else 48.sp, fontWeight = if (index == 1) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SharedChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = { if (enabled) onClick() }, enabled = enabled, modifier = modifier.heightIn(min = 80.dp),
        shape = RoundedCornerShape(18.dp), border = BorderStroke(if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)) {
        Text(label, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun SharedAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = { if (enabled) onClick() }, enabled = enabled,
        modifier = modifier.heightIn(min = 88.dp).widthIn(min = 200.dp), shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
        Text(label, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SharedMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SharedCaption(label)
        Text(value, fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SharedStopLine(marker: String, place: String, label: String, time: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(marker, Modifier.widthIn(min = 64.dp), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(place, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            SharedCaption(label)
        }
        time?.let { Text(it, fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun SharedCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 22.sp, lineHeight = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SharedError(text: String) {
    Text(text, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
}

private fun dropoffConfirmed(passenger: SharedPassengerDraft): Boolean = passenger.doorstepDropoffConfirmed &&
    passenger.doorstepConfirmedAtMillis != null && passenger.verification.vehicleAccess == AccessCheck.SUITABLE &&
    passenger.verification.pedestrianConnection == AccessCheck.SUITABLE && passenger.verification.checkedAtMillis != null &&
    passenger.verification.source.isNotBlank()

private fun stopLabel(stop: SharedScheduledStop): String = when (stop) {
    is SharedScheduledStop.Dropoff -> "${stop.passenger.name} 집 앞 하차"
    is SharedScheduledStop.Errand -> errandLabel(stop.errand.visit.kind)
}

private fun orderLabel(plan: SharedJourneyPlan): String = plan.stops.joinToString(" → ") { stop ->
    when (stop) {
        is SharedScheduledStop.Dropoff -> stop.passenger.name
        is SharedScheduledStop.Errand -> errandLabel(stop.errand.visit.kind)
    }
}

private fun errandLabel(kind: VisitKind): String = when (kind) {
    VisitKind.PICKUP -> "픽업"
    VisitKind.GROCERIES -> "장보기"
}

private fun clock(minute: Int?): String = minute?.let(::formatJourneyMinute) ?: "확인 필요"

private fun issueText(issue: SharedPlanIssue, plan: SharedJourneyPlan): String {
    fun person(id: String): String = plan.passengerArrivals.firstOrNull { it.passengerId == id }?.passengerName ?: "동승자"
    return when (issue) {
        is SharedPlanIssue.MissingDrivingEstimate -> "확인하지 못한 이동 구간이 있어요"
        is SharedPlanIssue.DriverOverBudget -> "허용한 추가시간보다 ${issue.excessMinutes}분 더 걸려요"
        is SharedPlanIssue.PassengerLate -> "${person(issue.passengerId)} 도착이 ${issue.lateMinutes}분 늦어요"
        is SharedPlanIssue.PassengerRideTooLong -> "${person(issue.passengerId)} 탑승 한도를 ${issue.excessMinutes}분 넘어요"
        is SharedPlanIssue.UnverifiedDropoff -> "${person(issue.passengerId)}의 하차 장소 확인이 필요해요"
        is SharedPlanIssue.UnsuitableDropoff -> "${person(issue.passengerId)}의 다른 하차 장소를 선택해주세요"
        SharedPlanIssue.ErrandNotReady -> "픽업 장소의 수령 준비를 확인해주세요"
        is SharedPlanIssue.ErrandDeadline -> "볼일 마감에 ${issue.lateMinutes}분 늦어요"
    }
}

private fun sharedTitle(stage: JourneyStage): String = when (stage) {
    JourneyStage.INPUT -> "모두 집으로 돌아가요"
    JourneyStage.COMPARE -> "어떤 순서가 좋을까요?"
    JourneyStage.REVIEW -> "이 순서로 함께 출발해요"
    JourneyStage.ACTIVE -> "한 명씩, 한 곳씩 집으로"
    JourneyStage.COMPLETE -> "함께 귀가를 마쳤어요"
}
