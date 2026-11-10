package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val VehiclePageWidth = 2_160.dp

@Composable
internal fun VehicleNextStopPanel(
    place: String,
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    progress: (@Composable () -> Unit)? = null,
    arrival: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            val destination: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(place, fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            val largeType = LocalDensity.current.fontScale > 1.15f
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 920.dp || largeType) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        destination()
                        arrival()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                        Box(Modifier.weight(1.7f)) { destination() }
                        Box(Modifier.weight(1f)) { arrival() }
                    }
                }
            }
            progress?.invoke()
        }
    }
}

@Composable
internal fun VehicleRouteDetails(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("전체 순서", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(if (expanded) "접기" else "펼치기", style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
            }
        }
    }
}

@Composable
internal fun VehicleCompletionPanel(
    home: String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(horizontal = 44.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(Modifier.size(80.dp), shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✓", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(home, fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold)
            Text(summary, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
    }
}
