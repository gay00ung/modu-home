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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.moduhome.app.core.voice.JourneyVoiceCommand
import io.moduhome.app.core.voice.VoiceInputState

@Composable
internal fun VehicleVoiceDialog(
    state: VoiceInputState,
    message: String?,
    command: JourneyVoiceCommand?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.86f
    val canConfirm = command != null && state == VoiceInputState.Idle
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.widthIn(max = 1200.dp).fillMaxWidth(0.86f).heightIn(max = maximumHeight),
            color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Text(if (state is VoiceInputState.Failed) "음성 입력을 확인해주세요" else if (command == null) "말로 계획 바꾸기" else "이렇게 바꿀까요?",
                    fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    when (state) {
                        VoiceInputState.Preparing -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("차량 음성 입력을 준비하고 있어요", fontSize = 30.sp, lineHeight = 42.sp)
                        }
                        is VoiceInputState.Listening -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("듣고 있어요", fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
                            if (state.partialText.isNotBlank()) {
                                Text(state.partialText.take(200), fontSize = 30.sp, lineHeight = 42.sp,
                                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        VoiceInputState.Stopping -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("음성 입력을 멈추고 있어요", fontSize = 30.sp, lineHeight = 42.sp)
                        }
                        is VoiceInputState.Failed -> {
                            Text("음성 입력 종료를 확인하지 못했어요", fontSize = 30.sp, lineHeight = 42.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(state.reason, fontSize = 28.sp, lineHeight = 38.sp)
                        }
                        VoiceInputState.Idle -> Unit
                    }
                    if (command != null) {
                        Text(command.confirmationText, fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
                        Text("적용을 누르면 이번 계획에 반영해요", fontSize = 28.sp, lineHeight = 38.sp)
                    } else {
                        if (!message.isNullOrBlank() && state !is VoiceInputState.Failed) {
                            Text(message, fontSize = 28.sp, lineHeight = 38.sp)
                        }
                        if (state is VoiceInputState.Failed) {
                            Text("이 화면을 닫고 터치로 계획을 선택해주세요", fontSize = 28.sp, lineHeight = 38.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (state == VoiceInputState.Idle && !message.isNullOrBlank()) {
                            Text("다시 말하려면 닫은 뒤 ‘말로 선택’을 눌러주세요", fontSize = 28.sp, lineHeight = 38.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (state != VoiceInputState.Stopping) {
                            Text("‘장도 보고 갈래’ 또는 ‘추가 20분’", fontSize = 28.sp, lineHeight = 40.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 88.dp),
                        shape = RoundedCornerShape(18.dp)) {
                        Text("닫기", fontSize = 28.sp)
                    }
                    Button(onClick = { if (canConfirm) onConfirm() }, enabled = canConfirm,
                        modifier = Modifier.widthIn(min = 260.dp).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("적용", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
