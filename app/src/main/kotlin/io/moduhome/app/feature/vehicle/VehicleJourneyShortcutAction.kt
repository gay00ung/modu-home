package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun VehicleJourneyShortcutAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 80.dp)) {
        Text(label, fontSize = 28.sp)
    }
}
