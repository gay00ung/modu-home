package io.moduhome.app.feature.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.moduhome.app.core.planning.VisitKind
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun VehiclePhoneConnectionDialog(
    state: ProposalRelayState,
    configuredBaseUrl: String,
    actions: ProposalRelayActions,
    modifier: Modifier = Modifier,
) {
    val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.90f
    Dialog(onDismissRequest = actions.dismissVehicle, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.widthIn(max = 1280.dp).fillMaxWidth(0.92f).heightIn(max = maximumHeight),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("휴대폰 연결", fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    state.message?.let { Text(it, fontSize = 28.sp, lineHeight = 40.sp, color = MaterialTheme.colorScheme.primary) }
                    val receiver = state.receiver
                    if (receiver == null) {
                        Text("휴대폰에서 보낸 하차지와 볼일을 받아요. 운전자가 확인한 뒤 계획에 반영해요",
                            fontSize = 28.sp, lineHeight = 40.sp)
                        Text("연결은 15분 동안 열려요. 휴대폰에서 QR 링크를 열거나 QR 이미지를 선택해주세요",
                            fontSize = 28.sp, lineHeight = 40.sp)
                        Text("연결 주소 · ${if (configuredBaseUrl.isBlank()) "현재 연결 사용 불가" else relayServerLabel(configuredBaseUrl)}",
                            fontSize = 28.sp, lineHeight = 40.sp)
                        if (state.seedChoices.isNotEmpty()) {
                            Text("선택한 볼일도 함께 공유", fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
                            state.seedChoices.forEach { choice ->
                                RelayLargeChoice(
                                    label = "${if (choice.kind == VisitKind.PICKUP) "픽업" else "장보기"} · ${choice.place.name}",
                                    selected = state.selectedSeedKind == choice.kind,
                                    enabled = !state.busy,
                                    onClick = { actions.chooseSeed(choice.kind) },
                                )
                            }
                            RelayLargeChoice("볼일 공유 없이 연결", state.selectedSeedKind == null, !state.busy,
                                onClick = { actions.chooseSeed(null) })
                            Text(if (state.selectedSeedKind == null) "차량의 볼일을 공유하지 않고 휴대폰만 연결해요"
                                else "선택한 한 곳의 장소와 볼일 조건만 공유해요", fontSize = 28.sp, lineHeight = 40.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("공유할 볼일 없이도 연결할 수 있어요. 휴대폰에서 하차지나 새 볼일을 작성해주세요",
                                fontSize = 28.sp, lineHeight = 40.sp)
                        }
                    } else {
                        Text("휴대폰에서 QR 링크를 열거나 QR 이미지를 선택해주세요", fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
                        state.qr?.let { qr ->
                            Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                                Image(qr, "동승자 휴대폰 연결 QR", Modifier.padding(16.dp).size(336.dp))
                            }
                        }
                        Text("연결 주소 · ${relayServerLabel(receiver.sender.baseUrl)}", fontSize = 28.sp, lineHeight = 40.sp)
                        Text("${relayExpiryLabel(receiver.expiresAtMillis)}까지 연결", fontSize = 28.sp, lineHeight = 40.sp)
                        Text("차량 앱을 열면 제안을 받아요. 받은 제안에서 확인해주세요",
                            fontSize = 28.sp, lineHeight = 40.sp)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    OutlinedButton(onClick = actions.dismissVehicle, enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("닫기", fontSize = 28.sp)
                    }
                    Button(onClick = if (state.receiver == null) actions.createRoom else actions.closeRoom,
                        enabled = !state.busy && (state.receiver != null || configuredBaseUrl.isNotBlank()),
                        modifier = Modifier.weight(1.4f).heightIn(min = 88.dp), shape = RoundedCornerShape(18.dp)) {
                        Text(if (state.busy) "연결 확인 중" else if (state.receiver == null) "연결 시작" else "연결 종료",
                            fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PhoneVehicleJoinDialog(state: ProposalRelayState, actions: ProposalRelayActions, modifier: Modifier = Modifier) {
    val candidate = state.candidate ?: return
    val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.90f
    Dialog(onDismissRequest = actions.dismissJoin, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.widthIn(max = 900.dp).fillMaxWidth(0.92f).heightIn(max = maximumHeight),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("차량 연결", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("차량 화면과 같은 연결 주소인지 확인해주세요")
                    Text(relayServerLabel(candidate.baseUrl), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${relayExpiryLabel(candidate.expiresAtMillis)}까지 연결할 수 있어요")
                    Text("연결 후 하차지나 볼일을 확인하고 보내요")
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = actions.dismissJoin, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text("닫기")
                    }
                    Button(onClick = actions.joinVehicle, enabled = !state.busy, modifier = Modifier.weight(1.5f).heightIn(min = 56.dp)) {
                        Text(if (state.busy) "연결 중" else "이 주소로 연결")
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayLargeChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), shape = RoundedCornerShape(18.dp)) {
        Text(if (selected) "✓ $label" else label, fontSize = 28.sp, lineHeight = 40.sp)
    }
}

internal fun relayServerLabel(baseUrl: String): String = runCatching {
    val uri = URI(baseUrl)
    "${uri.scheme}://${uri.rawAuthority}"
}.getOrElse { "연결 주소 확인 필요" }

internal fun relayExpiryLabel(atMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))
