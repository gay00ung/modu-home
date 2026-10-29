package io.moduhome.app.feature.vehicle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyActions
import io.moduhome.app.feature.home.JourneyDraft
import io.moduhome.app.feature.home.VehicleNavigationUiState
import io.moduhome.app.feature.home.VisitDraft
import io.moduhome.app.ui.theme.ModuHomeTheme

/** Read-only layout sample: no preferences, vehicle SDK, microphone, or network access. */
@Preview(name = "차량 가로 화면", widthDp = 1274, heightDp = 855)
@Preview(name = "차량 세로 화면", widthDp = 840, heightDp = 1184)
@Composable
fun VehicleLayoutPreview() {
    ModuHomeTheme(vehicle = true) {
        Column(Modifier.fillMaxSize()) {
            Text("화면 배치 예시 · 실제 조작 없음", fontSize = 18.sp, lineHeight = 26.sp)
            VehicleJourneyScreen(
                state = HomeUiState(JourneyDraft(
                    home = "예시 집", pickup = VisitDraft(place = "예시 픽업 장소", stay = "10"),
                    groceries = VisitDraft(place = "예시 장보기 장소", stay = "20"),
                )),
                actions = JourneyActions(), vehicle = VehicleNavigationUiState(),
                editingAllowed = true, restrictionMessage = null,
                onOpenDetails = {}, onOpenShared = {}, onOpenPhoneConnection = {}, onVoice = {},
                voiceAvailable = true, modifier = Modifier.weight(1f),
            )
        }
    }
}
