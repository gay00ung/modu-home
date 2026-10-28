package io.moduhome.app.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.core.planning.*
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.ui.theme.ModuHomeTheme
import java.time.LocalDate

internal data class JourneyActions(
    val onDraftChange: (JourneyDraft) -> Unit = {},
    val onCompare: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onReview: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onCompleteVisit: (VisitKind) -> Unit = {},
    val onFinish: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onNewJourney: () -> Unit = {},
    val onChooseNavigationPlace: (NavigationPlaceRole) -> Unit = {},
    val onReadNavigation: () -> Unit = {},
    val onReconnectNavigation: () -> Unit = {},
    val onOpenProposals: () -> Unit = {},
    val onEstimateTrip: (NavigationPlaceRole) -> Unit = {},
)

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    actions: JourneyActions,
    modifier: Modifier = Modifier,
    vehicle: VehicleNavigationUiState = VehicleNavigationUiState(),
) {
    val stage = state.progress.stage
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth > maxHeight && maxHeight < 500.dp
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { JourneyHeader(stage, compact) },
            bottomBar = { JourneyFooter(state, actions, vehicle, compact, beforeAction = { keyboard?.hide() }) },
        ) { padding ->
            key(stage) {
                JourneyWorkspace(
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    mainContent = {
                        val title = when (stage) {
                            JourneyStage.INPUT -> "픽업하고, 장도 보고 갈까요?"
                            JourneyStage.COMPARE -> "어떤 순서로 돌아갈까요?"
                            JourneyStage.REVIEW -> "귀가와 볼일을 한 계획으로"
                            JourneyStage.ACTIVE -> "선택한 순서대로 하나씩"
                            JourneyStage.COMPLETE -> "오늘 할 일까지, 모두 끝"
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                            Text(when (stage) {
                                JourneyStage.INPUT -> "출발 전 · 가는김에"
                                JourneyStage.COMPARE -> "계획 비교 · ${state.plans.size}가지 순서"
                                JourneyStage.REVIEW -> "계획 확정 · 순서 확인"
                                JourneyStage.ACTIVE -> "진행 중 · ${state.progress.completedVisits}/${state.selectedPlan?.visits?.size ?: 0}건 완료"
                                JourneyStage.COMPLETE -> "귀가 완료"
                            }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            if (!compact) Text(when (stage) {
                                JourneyStage.INPUT -> "들를 곳과 머무를 시간을 정해주세요"
                                JourneyStage.COMPARE -> "걷는 시간과 매장 체류까지 함께 계산했어요"
                                JourneyStage.REVIEW -> "방문 순서와 시간을 확인하고 시작하세요"
                                JourneyStage.ACTIVE -> "볼일을 마친 뒤 완료를 눌러주세요"
                                JourneyStage.COMPLETE -> "미뤄둔 볼일을 마치고 집에 도착했어요"
                            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when (stage) {
                            JourneyStage.INPUT -> JourneyInputContent(state, actions.onDraftChange)
                            JourneyStage.COMPARE -> ComparisonContent(state, actions.onSelect)
                            JourneyStage.REVIEW -> ReviewContent(state, compact)
                            JourneyStage.ACTIVE -> ActiveContent(state)
                            JourneyStage.COMPLETE -> CompleteContent(state)
                        }
                    },
                    detailContent = {
                        OutlinedButton(onClick = actions.onOpenProposals, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("함께 정하는 볼일 · 보내기 / 받기")
                        }
                        if (stage != JourneyStage.COMPLETE) {
                            VehicleNavigationContent(state, vehicle, actions)
                            HorizontalDivider()
                        }
                        JourneySummary(state)
                    },
                )
            }
        }
    }
}

@Composable
private fun JourneyHeader(stage: JourneyStage, compact: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = if (compact) 8.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RouteMark()
                Text("모두 집으로", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(if (compact) compactStage(stage) else "가는김에", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val current = when (stage) {
                JourneyStage.INPUT -> 0
                JourneyStage.COMPARE -> 1
                JourneyStage.REVIEW -> 2
                JourneyStage.ACTIVE, JourneyStage.COMPLETE -> 3
            }
            if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("입력", "비교", "확정", if (stage == JourneyStage.COMPLETE) "완료" else "진행").forEachIndexed { index, label ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val highlighted = index <= current
                        Box(Modifier.size(24.dp).background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text(if (index < current || stage == JourneyStage.COMPLETE) "✓" else "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ComparisonContent(state: HomeUiState, onSelect: (String) -> Unit) {
    val plans = state.plans
    val recommended = plans.firstOrNull { it.isValid }?.id
    plans.forEach { plan ->
        val selected = plan.id == state.progress.selectedPlanId
        Surface(
            modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(plan.id) }),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RadioButton(selected, onClick = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(planTitle(plan), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(when {
                            plan.id == recommended -> "추천 · 조건에 맞는 가장 짧은 계획"
                            plan.isValid -> "조건에 맞는 계획"
                            else -> "조건 확인 필요"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TimeMetric("집 도착 계획", formatJourneyMinute(plan.homeArrivalMinute), Modifier.weight(1f), emphasized = selected)
                    TimeMetric("추가시간", "+${plan.additionalMinutes}분", Modifier.weight(1f))
                }
                Text("주행 ${plan.drivingMinutes}분 · 왕복 도보 ${plan.walkingMinutes}분 · 체류 ${plan.stayMinutes}분",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlanIssues(plan)
            }
        }
    }
}

@Composable
private fun ReviewContent(state: HomeUiState, compact: Boolean) {
    val plan = state.selectedPlan ?: return
    if (!compact) SectionHeading("이번 방문 순서", subtitle = "${state.draft.departure} 출발 · ${plan.visits.size}곳 경유")
    JourneyTimeline(state.draft.origin, state.draft.home, state.validation.request?.departureMinute ?: 0, plan)
    PlanIssues(plan)
    Text("출발 후에는 완료 확인에 집중할 수 있도록 입력 화면을 닫아요",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ActiveContent(state: HomeUiState) {
    val plan = state.selectedPlan ?: return
    val next = plan.visits.getOrNull(state.progress.completedVisits)
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (next == null) "다음은 집으로" else "다음 들를 곳", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(next?.visit?.place ?: state.draft.home, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(if (next == null) "볼일을 모두 마쳤어요 · 집에 도착하면 마무리하세요"
                else "${visitLabel(next.visit.kind)} · 매장 체류 ${next.visit.stayMinutes}분",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SectionHeading("남은 이동 순서")
    JourneyTimeline(state.draft.origin, state.draft.home, state.validation.request?.departureMinute ?: 0,
        plan, completedVisits = state.progress.completedVisits, active = true)
}

@Composable
private fun CompleteContent(state: HomeUiState) {
    val plan = state.selectedPlan ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("${plan.visits.size}건의 볼일을 마쳤어요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(plan.visits.joinToString(" · ") { it.visit.place }, style = MaterialTheme.typography.bodyMedium)
        }
    }
    JourneyTimeline(state.draft.origin, state.draft.home, state.validation.request?.departureMinute ?: 0,
        plan, completedVisits = plan.visits.size, finished = true)
}

@Composable
private fun JourneySummary(state: HomeUiState) {
    val plan = state.selectedPlan ?: state.plans.firstOrNull()
    val request = state.validation.request
    val complete = state.progress.stage == JourneyStage.COMPLETE
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (complete) "이번 귀가 계획" else "볼일을 마치고 집까지", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(plan?.let { formatJourneyMinute(it.homeArrivalMinute) } ?: "—",
            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(if (plan != null) "${state.draft.home} · 총 ${plan.totalMinutes}분" else "장소와 시간을 입력하면 계산해요",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TimeMetric("곧바로 귀가", request?.let { formatJourneyMinute(it.departureMinute + it.travel.directHome) } ?: "—", Modifier.weight(1f))
            TimeMetric("총 추가시간", plan?.let { "+${it.additionalMinutes}분" } ?: "—", Modifier.weight(1f), emphasized = true)
        }
        if (plan != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryLine("주행", "${plan.drivingMinutes}분")
                SummaryLine("주차 후 왕복 도보", "${plan.walkingMinutes}분")
                SummaryLine("매장 체류", "${plan.stayMinutes}분")
                SummaryLine("허용 추가시간", "${state.draft.budget}분")
            }
            PlanIssues(plan)
            if (plan.isValid) Text(if (complete) "계획한 볼일과 귀가를 모두 마쳤어요" else "✓ 선택한 시간 한도 안에 들어와요",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        Text("직접 설정한 이동·도보·체류시간 기준", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun JourneyFooter(
    state: HomeUiState,
    actions: JourneyActions,
    vehicle: VehicleNavigationUiState,
    compact: Boolean,
    beforeAction: () -> Unit,
) {
    val stage = state.progress.stage
    val plan = state.selectedPlan
    val next = plan?.visits?.getOrNull(state.progress.completedVisits)
    val anyValid = state.plans.any { it.isValid }
    val needsPlaces = vehicle.connection != NavigationConnection.Unsupported &&
        plan?.let { state.draft.navigationPlan(it) } == null
    val primaryText = when (stage) {
        JourneyStage.INPUT -> "계획 비교 →"
        JourneyStage.COMPARE -> if (anyValid) "이 순서 확인 →" else "조건 수정"
        JourneyStage.REVIEW -> when {
            vehicle.sending -> "차량 내비 확인 중"
            vehicle.connection == NavigationConnection.Unsupported -> "계획 기록 시작"
            needsPlaces -> "내비 장소 선택"
            else -> "차량 내비로 시작"
        }
        JourneyStage.ACTIVE -> next?.let { "${visitLabel(it.visit.kind)} 완료" } ?: "집 도착 · 마무리"
        JourneyStage.COMPLETE -> "새 계획 만들기"
    }
    val secondaryText = when (stage) {
        JourneyStage.INPUT, JourneyStage.COMPLETE -> null
        JourneyStage.COMPARE -> if (anyValid) "입력 수정" else null
        JourneyStage.REVIEW -> "다시 비교"
        JourneyStage.ACTIVE -> "계획 중단"
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = if (compact) 8.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (secondaryText != null) TextButton(enabled = !vehicle.sending && !vehicle.reading, onClick = {
                    beforeAction()
                    when (stage) {
                        JourneyStage.COMPARE -> actions.onEdit()
                        JourneyStage.REVIEW -> actions.onBack()
                        JourneyStage.ACTIVE -> actions.onCancel()
                        JourneyStage.INPUT, JourneyStage.COMPLETE -> Unit
                    }
                }, modifier = Modifier.heightIn(min = if (compact) 48.dp else 56.dp)) { Text(secondaryText) }
                if (compact) Spacer(Modifier.weight(1f))
                val buttonModifier = if (compact) Modifier.widthIn(min = 220.dp, max = 360.dp) else Modifier.weight(1f)
                Button(
                    enabled = !vehicle.sending && !vehicle.reading &&
                        (stage != JourneyStage.COMPARE || !anyValid || plan?.isValid == true) &&
                        (stage != JourneyStage.REVIEW || vehicle.connection == NavigationConnection.Ready ||
                            vehicle.connection == NavigationConnection.Unsupported),
                    onClick = {
                        beforeAction()
                        when (stage) {
                            JourneyStage.INPUT -> actions.onCompare()
                            JourneyStage.COMPARE -> if (anyValid) actions.onReview() else actions.onEdit()
                            JourneyStage.REVIEW -> if (needsPlaces) actions.onEdit() else actions.onStart()
                            JourneyStage.ACTIVE -> if (next != null) actions.onCompleteVisit(next.visit.kind) else actions.onFinish()
                            JourneyStage.COMPLETE -> actions.onNewJourney()
                        }
                    }, modifier = buttonModifier.heightIn(min = if (compact) 48.dp else 56.dp), shape = RoundedCornerShape(12.dp),
                ) { Text(primaryText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 1200, heightDp = 760)
@Composable
private fun VehiclePlanPreview() {
    val draft = JourneyDraft(dateEpochDay = LocalDate.of(2026, 10, 28).toEpochDay())
    val input = HomeUiState(draft)
    ModuHomeTheme { HomeScreen(input.copy(progress = input.progress.compare(input.plans)), JourneyActions()) }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PhoneInputPreview() {
    ModuHomeTheme { HomeScreen(HomeUiState(JourneyDraft(dateEpochDay = LocalDate.of(2026, 10, 28).toEpochDay())), JourneyActions()) }
}

private fun compactStage(stage: JourneyStage): String = when (stage) {
    JourneyStage.INPUT -> "1/4 · 입력"
    JourneyStage.COMPARE -> "2/4 · 비교"
    JourneyStage.REVIEW -> "3/4 · 확정"
    JourneyStage.ACTIVE -> "4/4 · 진행"
    JourneyStage.COMPLETE -> "4/4 · 완료"
}
