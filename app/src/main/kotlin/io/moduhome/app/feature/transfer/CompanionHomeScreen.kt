package io.moduhome.app.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("모두 집으로 · 동승자", fontSize = 20.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("내릴 곳과 볼일을\n함께 보내요", fontSize = 36.sp, lineHeight = 44.sp,
                fontWeight = FontWeight.Bold)
            Text("내 하차지나 들를 곳을 보내면 운전자가 모두의 귀가 순서를 함께 정해요",
                fontSize = 18.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (connected) "차량과 연결됐어요" else "차량의 QR로 연결해주세요",
                    fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(if (connected) "보낸 제안은 차량 수신함에서 운전자가 확인해요"
                    else "차량에서 ‘휴대폰 연결’을 열고 QR 링크를 열거나 QR 이미지를 선택하세요",
                    fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message?.let {
                Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.primary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCreateDropoff, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text("내 하차지 보내기", fontSize = 18.sp, lineHeight = 26.sp)
                }
                Button(
                    onClick = if (connected) onCreateErrand else onReadQr,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Text(if (connected) "주소로 새 볼일 만들기" else "차량 연결 QR 이미지 열기",
                        fontSize = 18.sp, lineHeight = 26.sp)
                }
                if (!connected) OutlinedButton(onClick = onCreateErrand, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text("연결 전 볼일 만들기", fontSize = 18.sp, lineHeight = 26.sp)
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
