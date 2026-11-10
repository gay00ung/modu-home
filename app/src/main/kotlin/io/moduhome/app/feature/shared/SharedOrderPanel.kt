package io.moduhome.app.feature.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.moduhome.app.R
import io.moduhome.app.core.planning.formatJourneyMinute
import io.moduhome.app.core.shared.SharedJourneyPlan
import io.moduhome.app.core.shared.SharedScheduledStop

private data class OrderArrival(
    val position: Int?,
    val place: String,
    val detail: String?,
    val minute: Int?,
    val maxPlaceLines: Int,
)

@Composable
internal fun SharedOrderPanel(
    plan: SharedJourneyPlan,
    modifier: Modifier = Modifier,
    departureHome: String? = null,
) {
    val confirmingDeparture = departureHome != null
    val sweep = rememberOrderSweep(enabled = !confirmingDeparture && plan.stops.isNotEmpty())
    val placeLines = if (confirmingDeparture) Int.MAX_VALUE else 2
    val extraTime = plan.driverAdditionalMinutes?.let { "직행 +${it}분" }
    val homeDetail = if (confirmingDeparture) listOfNotNull("집 도착", extraTime).joinToString(" · ") else extraTime
    val arrivals = plan.stops.mapIndexed { index, stop ->
        OrderArrival(index + 1, stop.place.point.name, shortStopLabel(stop), stop.arrivalMinute, placeLines)
    } + OrderArrival(null, departureHome ?: "집 도착", homeDetail, plan.driverHomeArrivalMinute, placeLines)
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
            if (confirmingDeparture) {
                OrderNote(estimate, Modifier.align(Alignment.End))
            } else if (canLayOutSideBySide) {
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
                    HorizontalOrder(arrivals, sweep, Modifier.padding(48.dp))
                } else {
                    VerticalOrder(arrivals, sweep, inlineClock = canLayOutSideBySide, modifier = Modifier.padding(28.dp))
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
private fun HorizontalOrder(arrivals: List<OrderArrival>, sweep: State<Float>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        arrivals.forEachIndexed { index, arrival ->
            val hasNext = index < arrivals.lastIndex
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OrderMarker(arrival.position, Modifier.size(72.dp))
                    if (hasNext) {
                        OrderConnector(
                            phase = { sweep.value * arrivals.lastIndex - index },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
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
private fun VerticalOrder(
    arrivals: List<OrderArrival>,
    sweep: State<Float>,
    inlineClock: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        arrivals.forEachIndexed { index, arrival ->
            if (index > 0) {
                OrderConnector(
                    phase = { sweep.value * arrivals.lastIndex - (index - 1) },
                    modifier = Modifier.fillMaxWidth().padding(start = 96.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = if (inlineClock) Alignment.CenterVertically else Alignment.Top,
            ) {
                OrderMarker(arrival.position, Modifier.size(72.dp))
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
internal fun SharedProgressRail(
    stopCount: Int,
    completedStops: Int,
    guidanceReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val next = completedStops.coerceIn(0, stopCount)
    val sweep = rememberOrderSweep(enabled = next > 0 && guidanceReady)
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0..stopCount) {
            if (index > 0) {
                OrderConnector(
                    phase = { if (index == next) sweep.value else 1f },
                    highlighted = index == next,
                    modifier = Modifier.weight(1f),
                )
            }
            OrderMarker(
                position = if (index == stopCount) null else index + 1,
                modifier = Modifier.size(56.dp),
                state = when {
                    index < next -> OrderMarkerState.COMPLETE
                    index == next -> OrderMarkerState.NEXT
                    else -> OrderMarkerState.WAITING
                },
            )
        }
    }
}

@Composable
private fun rememberOrderSweep(enabled: Boolean): State<Float> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!enabled || !lifecycle.value.isAtLeast(Lifecycle.State.RESUMED)) {
        return remember { mutableFloatStateOf(1f) }
    }
    return rememberInfiniteTransition(label = "route sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep position",
    )
}

@Composable
private fun OrderConnector(
    phase: () -> Float,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val track = if (highlighted) {
        lerp(colors.outlineVariant, colors.primary, 0.28f)
    } else {
        lerp(colors.outlineVariant, colors.primaryContainer, 0.30f)
    }
    Spacer(
        modifier.height(20.dp).drawWithCache {
            val strokeWidth = 16.dp.toPx().coerceAtMost(size.width)
            val capInset = strokeWidth / 2f
            val start = Offset(capInset, size.height / 2f)
            val end = Offset(size.width - capInset, start.y)
            val length = end.x - start.x
            val radius = (length * 0.30f).coerceIn(1f, 240.dp.toPx())
            val lavender = lerp(colors.primary, Color(0xFF9566DE), 0.76f)
            val blue = lerp(colors.primary, Color(0xFF617CF0), 0.70f)
            val skyBlue = lerp(colors.primary, Color(0xFF59C5F4), 0.78f)
            val glow = Brush.horizontalGradient(
                0f to lavender.copy(alpha = 0f),
                0.23f to lavender.copy(alpha = 0.82f),
                0.51f to blue.copy(alpha = 0.94f),
                0.75f to skyBlue.copy(alpha = 0.86f),
                1f to skyBlue.copy(alpha = 0f),
                startX = -radius,
                endX = radius,
            )
            onDrawBehind {
                drawLine(track, start, end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                val fraction = phase()
                if (fraction > 0f && fraction < 1f && length > 0f) {
                    val glowCenter = start.x - radius + (length + 2f * radius) * fraction
                    translate(left = glowCenter) {
                        drawLine(
                            glow,
                            start.copy(x = start.x - glowCenter),
                            end.copy(x = end.x - glowCenter),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        },
    )
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
private fun OrderNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 28.sp, lineHeight = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private enum class OrderMarkerState { PREVIEW, COMPLETE, NEXT, WAITING }

@Composable
private fun OrderMarker(
    position: Int?,
    modifier: Modifier = Modifier,
    state: OrderMarkerState = OrderMarkerState.PREVIEW,
) {
    val colors = MaterialTheme.colorScheme
    val accent = state == OrderMarkerState.NEXT || (state == OrderMarkerState.PREVIEW && position == null)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = when {
            accent -> colors.primary
            state == OrderMarkerState.PREVIEW -> colors.primaryContainer
            else -> colors.surface
        },
        contentColor = when {
            accent -> colors.onPrimary
            state == OrderMarkerState.PREVIEW -> colors.primary
            else -> colors.onSurfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state == OrderMarkerState.COMPLETE) {
                Text("✓", fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
            } else if (position == null) {
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
            maxLines = arrival.maxPlaceLines,
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
