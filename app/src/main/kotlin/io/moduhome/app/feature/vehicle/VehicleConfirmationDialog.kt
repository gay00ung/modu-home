package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun VehicleConfirmationDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.88f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.widthIn(max = 1200.dp).fillMaxWidth(0.86f).heightIn(max = maximumHeight),
            color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Text(title, fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                Text(description, Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    fontSize = 30.sp, lineHeight = 42.sp)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 88.dp),
                        shape = RoundedCornerShape(18.dp)) { Text("닫기", fontSize = 28.sp) }
                    Button(onClick = onConfirm, modifier = Modifier.widthIn(min = 280.dp).heightIn(min = 88.dp),
                        shape = RoundedCornerShape(18.dp)) {
                        Text(confirmLabel, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
