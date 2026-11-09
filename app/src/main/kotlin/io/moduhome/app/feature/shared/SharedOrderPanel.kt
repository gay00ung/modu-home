package io.moduhome.app.feature.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.R
import io.moduhome.app.core.planning.formatJourneyMinute
import io.moduhome.app.core.shared.SharedJourneyPlan
import io.moduhome.app.core.shared.SharedScheduledStop

private data class OrderArrival(
    val position: Int?,
    val place: String,
    val detail: String?,
    val minute: Int?,
)

@Composable
internal fun SharedOrderPanel(plan: SharedJourneyPlan, modifier: Modifier = Modifier) {
    val arrivals = plan.stops.mapIndexed { index, stop ->
        OrderArrival(index + 1, stop.place.point.name, shortStopLabel(stop), stop.arrivalMinute)
    } + OrderArrival(null, "집 도착", plan.driverAdditionalMinutes?.let { "직행 +${it}분" }, plan.driverHomeArrivalMinute)
    val title = when {
        plan.stops.any { it is SharedScheduledStop.Errand } -> "방문 순서"
        plan.stops.isNotEmpty() -> "하차 순서"
        else -> "귀가 순서"
    }
    val estimate = when {
        plan.includesSampleTimes -> "예시 시간"
        plan.includesUserEstimates -> "입력한 시간"
        else -> "실시간 교통 제외"
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val canLayOutSideBySide = fontScale <= 1.15f && maxWidth >= 840.dp
        val useHorizontalRoute = canLayOutSideBySide && maxWidth >= (arrivals.size * 440 + 96).dp
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (canLayOutSideBySide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OrderHeading(title)
                    OrderNote(estimate)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OrderHeading(title)
                    OrderNote(estimate)
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(36.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                if (useHorizontalRoute) {
                    HorizontalOrder(arrivals, Modifier.padding(48.dp))
                } else {
                    VerticalOrder(arrivals, inlineClock = canLayOutSideBySide, modifier = Modifier.padding(28.dp))
                }
            }
        }
    }
}

@Composable
internal fun SharedOrderSwapButton(
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 112.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_swap_order), contentDescription = null, modifier = Modifier.size(40.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                if (maxWidth >= 1_000.dp && fontScale <= 1.15f) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SwapLabel()
                        if (detail.isNotEmpty()) SwapDetail(detail, Modifier.weight(1f), TextAlign.End)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SwapLabel()
                        if (detail.isNotEmpty()) SwapDetail(detail)
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizontalOrder(arrivals: List<OrderArrival>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        arrivals.forEachIndexed { index, arrival ->
            val hasNext = index < arrivals.lastIndex
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OrderMarker(arrival.position)
                    if (hasNext) {
                        HorizontalDivider(
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            thickness = 4.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
                OrderPlace(
                    arrival,
                    large = true,
                    modifier = Modifier.padding(top = 24.dp, end = if (hasNext) 36.dp else 0.dp),
                )
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.weight(1f))
                OrderClock(arrival, large = true)
            }
        }
    }
}

@Composable
private fun VerticalOrder(arrivals: List<OrderArrival>, inlineClock: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        arrivals.forEachIndexed { index, arrival ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 96.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = if (inlineClock) Alignment.CenterVertically else Alignment.Top,
            ) {
                OrderMarker(arrival.position)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrderPlace(arrival, large = false)
                    if (!inlineClock) OrderClock(arrival, large = false)
                }
                if (inlineClock) OrderClock(arrival, large = false)
            }
        }
    }
}

@Composable
private fun OrderHeading(title: String) {
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        fontSize = 52.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun OrderNote(text: String) {
    Text(text, fontSize = 28.sp, lineHeight = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OrderMarker(position: Int?) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        color = if (position == null) colors.primary else colors.primaryContainer,
        contentColor = if (position == null) colors.onPrimary else colors.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (position == null) {
                Icon(painterResource(R.drawable.ic_journey_notification), contentDescription = null, modifier = Modifier.size(38.dp))
            } else {
                Text(position.toString(), fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OrderPlace(arrival: OrderArrival, large: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        arrival.detail?.let { OrderNote(it) }
        Text(
            arrival.place,
            fontSize = if (large) 52.sp else 40.sp,
            lineHeight = if (large) 64.sp else 48.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OrderClock(arrival: OrderArrival, large: Boolean) {
    val home = arrival.position == null
    val time = arrival.minute?.let(::formatJourneyMinute)
    Text(
        text = buildAnnotatedString {
            if (time == null) {
                append("확인 필요")
            } else {
                append(time.substringBefore(' '))
                val day = time.substringAfter(' ', "")
                if (day.isNotEmpty()) {
                    append(' ')
                    withStyle(SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium)) { append(day) }
                }
            }
        },
        fontSize = when {
            time == null -> 32.sp
            large && home -> 88.sp
            large -> 76.sp
            home -> 64.sp
            else -> 56.sp
        },
        lineHeight = if (large) 96.sp else 72.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (home) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SwapLabel() {
    Text("순서 바꾸기", fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SwapDetail(detail: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(detail, modifier, fontSize = 32.sp, lineHeight = 44.sp, textAlign = textAlign)
}
