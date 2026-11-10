package io.moduhome.app.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moduhome.app.R

@Composable
internal fun CompanionHomeScreen(
    connected: Boolean,
    pendingProposalCount: Int,
    busy: Boolean,
    message: String?,
    onReadQr: () -> Unit,
    onCreateErrand: () -> Unit,
    onCreateDropoff: () -> Unit,
    onOpenProposals: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 540.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(36.dp), shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_journey_notification), contentDescription = null,
                                modifier = Modifier.size(22.dp))
                        }
                    }
                    Text("모두 집으로", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text("내릴 곳과 볼일을\n함께 보내요", fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (connected) "차량과 연결됐어요" else "차량 QR로 연결해주세요",
                            fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (connected) "보낸 제안은 운전자가 수신함에서 확인해요"
                            else "차량에서 ‘휴대폰 연결’을 열면 QR이 나와요",
                            fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                message?.let { Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.primary) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = if (connected) onCreateDropoff else onReadQr,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(if (connected) "내 하차지 보내기" else "차량 연결 QR 이미지 열기",
                            fontSize = 18.sp, lineHeight = 26.sp)
                    }
                    if (!connected) OutlinedButton(onClick = onCreateDropoff, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                        Text("내 하차지 고르기", fontSize = 18.sp, lineHeight = 26.sp)
                    }
                    OutlinedButton(onClick = onCreateErrand, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                        Text(if (connected) "주소로 새 볼일 만들기" else "연결 전 볼일 만들기", fontSize = 18.sp, lineHeight = 26.sp)
                    }
                    TextButton(onClick = onOpenProposals, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(if (pendingProposalCount > 0) "받은 제안 ${pendingProposalCount}개 · 전송 확인"
                            else "볼일과 차량 연결 확인", fontSize = 16.sp, lineHeight = 24.sp)
                    }
                }
            }
        }
    }
}
