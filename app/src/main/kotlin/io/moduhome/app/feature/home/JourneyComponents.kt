package io.moduhome.app.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.moduhome.app.core.planning.*

@Composable
internal fun JourneyWorkspace(
    mainContent: @Composable () -> Unit,
    detailContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 640.dp) {
            val compact = maxHeight < 500.dp
            val spacing = if (compact) 16.dp else 28.dp
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Column(Modifier.weight(1.35f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = spacing, bottom = spacing, end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 24.dp)) { mainContent() }
                VerticalDivider()
                Column(Modifier.weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = spacing, bottom = spacing, start = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 24.dp)) { detailContent() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                mainContent()
                HorizontalDivider()
                detailContent()
            }
        }
    }
}

@Composable
internal fun RouteMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(26.dp)) {
        scale(size.width / 26f, size.height / 26f, pivot = Offset.Zero) {
            val path = Path().apply { moveTo(6f, 20f); lineTo(6f, 15f); cubicTo(6f, 10f, 20f, 16f, 20f, 9f); lineTo(20f, 6f) }
            drawPath(path, color, style = Stroke(2f))
            drawCircle(color, 3f, Offset(6f, 22f), style = Stroke(2f))
            drawCircle(color, 3f, Offset(20f, 4f), style = Stroke(2f))
        }
    }
}

@Composable
internal fun SectionHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun JourneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    numeric: Boolean = false,
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(if (numeric) it.filter(Char::isDigit).take(3) else it.take(100)) },
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) { { Text(placeholder) } } else null,
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        suffix = if (numeric) { { Text("분") } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    )
}

@Composable
internal fun TimeMetric(label: String, value: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun PlanIssues(plan: ErrandJourneyPlan, modifier: Modifier = Modifier) {
    if (plan.issues.isEmpty()) return
    Surface(modifier, color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("이 조건을 확인해주세요", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            plan.issues.forEach { issue ->
                val name = plan.visits.firstOrNull { it.visit.kind == issue.visit }?.visit?.place.orEmpty()
                val text = when (issue.kind) {
                    PlanIssueKind.OVER_BUDGET -> "추가시간이 한도보다 ${issue.minutes}분 많아요"
                    PlanIssueKind.NOT_READY -> "$name 픽업 준비를 확인해주세요"
                    PlanIssueKind.DEADLINE -> "$name 마감보다 ${issue.minutes}분 늦어요"
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
internal fun JourneyTimeline(
    origin: String,
    home: String,
    departureMinute: Int,
    plan: ErrandJourneyPlan,
    modifier: Modifier = Modifier,
    completedVisits: Int = 0,
    active: Boolean = false,
    finished: Boolean = false,
) {
    Column(modifier) {
        TimelineRow(origin, formatJourneyMinute(departureMinute), "출발", false, active || finished, false)
        plan.visits.forEachIndexed { index, stop ->
            val complete = index < completedVisits || finished
            TimelineRow(stop.visit.place, formatJourneyMinute(stop.arrivalMinute),
                "${visitLabel(stop.visit.kind)} · 체류 ${stop.visit.stayMinutes}분" +
                    if (stop.visit.walkingMinutesOneWay > 0) " · 왕복 도보 ${stop.visit.walkingMinutesOneWay * 2}분" else "",
                active && index == completedVisits, complete, false)
        }
        TimelineRow(home, formatJourneyMinute(plan.homeArrivalMinute), "귀가", active && completedVisits == plan.visits.size, finished, true)
    }
}

@Composable
private fun TimelineRow(title: String, time: String, description: String, current: Boolean, complete: Boolean, last: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 78.dp)) {
        Column(Modifier.width(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(22.dp).background(
                if (current || complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                CircleShape), contentAlignment = Alignment.Center) {
                Text(if (complete) "✓" else "•", style = MaterialTheme.typography.labelMedium,
                    color = if (current || complete) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!last) Box(Modifier.width(1.dp).height(56.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium)
                Text(time, style = MaterialTheme.typography.bodyMedium,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (complete) "$description · 완료" else description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun visitLabel(kind: VisitKind): String = when (kind) {
    VisitKind.PICKUP -> "픽업"
    VisitKind.GROCERIES -> "장보기"
}

internal fun planTitle(plan: ErrandJourneyPlan): String = plan.visits.joinToString(" → ") { visitLabel(it.visit.kind) }
