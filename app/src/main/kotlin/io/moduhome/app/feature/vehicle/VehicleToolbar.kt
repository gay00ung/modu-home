package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Keeps the title readable while callers supply the actions available in their current stage. */
@Composable
internal fun VehicleToolbar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        if (availableWidth < 1320.dp || fontScale > 1.1f) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VehicleToolbarTitle(title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                VehicleToolbarTitle(title, Modifier.weight(1f))
                FlowRow(Modifier.widthIn(max = availableWidth * 0.65f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
            }
        }
    }
}

@Composable
private fun VehicleToolbarTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
}
