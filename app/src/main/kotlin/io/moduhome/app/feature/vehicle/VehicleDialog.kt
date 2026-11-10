package io.moduhome.app.feature.vehicle

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
internal fun VehicleDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val dismiss by rememberUpdatedState(onDismissRequest)
        val colors = MaterialTheme.colorScheme

        // Pleos can skip window dimming, so draw the scrim within the dialog window.
        SideEffect { window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.matchParentSize()
                    .background(colors.scrim.copy(alpha = 0.38f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { dismiss() }) },
            )
            Surface(
                modifier = modifier,
                shape = MaterialTheme.shapes.extraLarge,
                color = colors.surfaceBright,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 16.dp,
                content = content,
            )
        }
    }
}
