package io.moduhome.app.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.CompanionErrandDraft
import io.moduhome.app.core.transfer.CompanionErrandField
import io.moduhome.app.core.transfer.CompanionErrandResult
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.JourneyProposalInbox
import io.moduhome.app.data.transfer.ProposalLinkCodec
import io.moduhome.app.ui.theme.ModuHomeTheme
import java.time.ZoneId

/** Local rendering sample: no preferences, networking, or navigation gateway. */
@Preview(name = "휴대폰 볼일 편집", widthDp = 360, heightDp = 640)
@Composable
fun CompanionErrandEditorPreview() {
    val original = remember {
        val now = System.currentTimeMillis()
        ErrandProposal(
            "preview-errand", 1, now, now + ErrandProposal.MAX_LIFETIME_MILLIS,
            VisitKind.PICKUP, NavigationPoint("미리보기 픽업 장소", 37.5663, 126.9779, "화면 확인용 예시 장소"),
            stayMinutes = 10, walkingMinutesOneWay = 2, ready = false, deadlineAtMillis = now + 7_200_000,
        )
    }
    var draft by remember { mutableStateOf(CompanionErrandDraft.from(original, System.currentTimeMillis(), ZoneId.systemDefault())) }
    var errors by remember { mutableStateOf<Map<CompanionErrandField, String>>(emptyMap()) }
    var exported by remember { mutableStateOf<ErrandProposal?>(null) }
    var editorOpen by remember { mutableStateOf(true) }
    ModuHomeTheme(vehicle = false) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("휴대폰 화면 미리보기 · 실제 전송 없음")
            if (editorOpen) {
                CompanionErrandEditor(
                    draft = draft, errors = errors, editingAllowed = true,
                    onDraftChange = { draft = it; errors = emptyMap() },
                    onPrepare = {
                        when (val result = draft.createProposal(System.currentTimeMillis(), ZoneId.systemDefault())) {
                            is CompanionErrandResult.Invalid -> errors = result.errors
                            is CompanionErrandResult.Created -> { exported = result.proposal; editorOpen = false }
                        }
                    },
                    onDismiss = { editorOpen = false },
                    modifier = Modifier.size(360.dp, 640.dp),
                )
            } else if (exported == null) {
                Button(onClick = { editorOpen = true }) { Text("편집 다시 열기") }
            }
        }
        exported?.let { proposal ->
            ProposalContent(
                state = ProposalUiState(
                    inbox = JourneyProposalInbox.empty(),
                    message = "화면 확인용 예시예요. 실제 전송은 하지 않아요",
                    exportLink = ProposalLinkCodec.encode(proposal),
                    exportName = proposal.place.name,
                    exportedProposal = proposal,
                    canEditPrepared = true,
                ),
                actions = ProposalActions(
                    onInput = {}, onReceive = {}, onPaste = {}, onReadQr = {},
                    onApprove = { _, _ -> }, onDecline = { _, _ -> }, onExport = {},
                    onCopy = {}, onShare = {}, onDismiss = { exported = null; editorOpen = true },
                    onEditPrepared = { exported = null; editorOpen = true },
                    onBackToProposals = { exported = null; editorOpen = true },
                ),
                modifier = Modifier.widthIn(max = 360.dp).heightIn(max = 640.dp),
            )
        }
    }
}
