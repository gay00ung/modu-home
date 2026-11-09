package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun VehicleActionBar(
    caption: String?,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    val fontScale = LocalDensity.current.fontScale
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp)) {
                if (maxWidth < 1000.dp || fontScale > 1.1f) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (caption != null) VehicleActionCaption(caption)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            secondaryAction?.invoke()
                            VehiclePrimaryAction(actionLabel, actionEnabled, onAction, Modifier.weight(1f))
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        secondaryAction?.invoke()
                        if (caption != null) VehicleActionCaption(caption, Modifier.weight(1f))
                        else Spacer(Modifier.weight(1f))
                        VehiclePrimaryAction(actionLabel, actionEnabled, onAction, Modifier.widthIn(min = 420.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleActionCaption(caption: String, modifier: Modifier = Modifier) {
    Text(caption, modifier, fontSize = 28.sp, lineHeight = 38.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VehiclePrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = { if (enabled) onClick() }, enabled = enabled,
        modifier = modifier.heightIn(min = 96.dp), shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp)) {
        Text(label, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
    }
}
