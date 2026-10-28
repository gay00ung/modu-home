package io.moduhome.app

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.data.JourneyPreferences
import io.moduhome.app.feature.home.HomeScreen
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyActions
import io.moduhome.app.feature.home.JourneyDraft
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun ModuHomeApp() {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { JourneyPreferences(context) }
    fun freshDraft(previous: JourneyDraft = JourneyDraft()): JourneyDraft {
        val now = LocalDateTime.now()
        return previous.newJourney(departure = now.format(DateTimeFormatter.ofPattern("HH:mm")), dateEpochDay = now.toLocalDate().toEpochDay())
    }
    var state by remember(preferences) { mutableStateOf(preferences.load(freshDraft())) }
    var cancelRequested by remember { mutableStateOf(false) }
    fun update(next: HomeUiState) {
        state = next
        preferences.save(next)
    }
    val actions = JourneyActions(
        onDraftChange = { draft ->
            if (state.progress.stage == JourneyStage.INPUT) update(state.copy(draft = draft, progress = JourneyProgress()))
        },
        onCompare = {
            if (state.validation.request == null) update(state.copy(showErrors = true))
            else update(state.copy(progress = state.progress.compare(state.plans), showErrors = false))
        },
        onSelect = { id -> update(state.copy(progress = state.progress.select(id, state.plans))) },
        onReview = { update(state.copy(progress = state.progress.review(state.plans))) },
        onStart = { update(state.copy(progress = state.progress.start(state.plans, System.currentTimeMillis()))) },
        onCompleteVisit = { kind -> update(state.copy(progress = state.progress.completeVisit(state.plans, kind))) },
        onFinish = { update(state.copy(progress = state.progress.finish(state.plans, System.currentTimeMillis()))) },
        onBack = { update(state.copy(progress = state.progress.back())) },
        onEdit = { update(state.copy(progress = JourneyProgress(), showErrors = false)) },
        onCancel = { cancelRequested = true },
        onNewJourney = { update(HomeUiState(freshDraft(state.draft))) },
    )
    BackHandler(enabled = state.progress.stage != JourneyStage.INPUT) {
        when (state.progress.stage) {
            JourneyStage.ACTIVE -> cancelRequested = true
            JourneyStage.COMPLETE -> actions.onNewJourney()
            JourneyStage.COMPARE, JourneyStage.REVIEW -> actions.onBack()
            JourneyStage.INPUT -> Unit
        }
    }
    HomeScreen(state = state, actions = actions)
    if (cancelRequested) {
        AlertDialog(
            onDismissRequest = { cancelRequested = false },
            title = { Text("진행 중인 계획을 중단할까요?") },
            text = { Text("입력한 장소와 시간은 남겨둘게요") },
            confirmButton = { TextButton(onClick = {
                cancelRequested = false
                update(state.copy(progress = JourneyProgress(), showErrors = false))
            }) { Text("계획 중단") } },
            dismissButton = { TextButton(onClick = { cancelRequested = false }) { Text("계속 진행") } },
        )
    }
}
