package io.moduhome.app

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.RouteRequestResult
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.data.JourneyPreferences
import io.moduhome.app.data.navigation.PleosNavigationGateway
import io.moduhome.app.feature.home.HomeScreen
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyActions
import io.moduhome.app.feature.home.JourneyDraft
import io.moduhome.app.feature.home.NavigationPlacePicker
import io.moduhome.app.feature.home.NavigationPlaceRole
import io.moduhome.app.feature.home.PlacePickerUiState
import io.moduhome.app.feature.home.VehicleNavigationUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun ModuHomeApp() {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { JourneyPreferences(context) }
    val scope = rememberCoroutineScope()
    var gatewayRevision by remember { mutableIntStateOf(0) }
    val gateway = remember(context, gatewayRevision) { PleosNavigationGateway(context) }
    val connection by gateway.connection.collectAsStateWithLifecycle()
    DisposableEffect(gateway) {
        gateway.connect()
        onDispose { gateway.close() }
    }
    var sending by remember(gateway) { mutableStateOf(false) }
    var reading by remember(gateway) { mutableStateOf(false) }
    var navigationMessage by remember(gateway) { mutableStateOf<String?>(null) }
    var navigationError by remember(gateway) { mutableStateOf(false) }
    var navigationSnapshot by remember(gateway) { mutableStateOf<NavigationSnapshot?>(null) }
    var picker by remember { mutableStateOf<PlacePickerUiState?>(null) }
    var pickerRequest by remember { mutableLongStateOf(0L) }
    var pickerJob by remember { mutableStateOf<Job?>(null) }
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
    fun clearNavigationRead() {
        navigationMessage = null
        navigationError = false
        navigationSnapshot = null
    }
    fun dismissPicker() {
        pickerRequest += 1
        pickerJob?.cancel()
        picker = null
    }
    fun loadPlaces(role: NavigationPlaceRole) {
        if (state.progress.stage != JourneyStage.INPUT || sending) return
        pickerJob?.cancel()
        pickerRequest += 1
        val request = pickerRequest
        picker = PlacePickerUiState(role)
        pickerJob = scope.launch {
            val result = gateway.loadPlaces()
            if (request == pickerRequest) picker = PlacePickerUiState(role, loading = false, result = result)
        }
    }
    fun readNavigation() {
        if (reading || sending || connection != NavigationConnection.Ready) return
        reading = true
        scope.launch {
            try {
                val snapshot = gateway.currentRoute()
                navigationSnapshot = snapshot
                navigationError = snapshot == null
                navigationMessage = if (snapshot == null) "차량 내비의 경로를 읽지 못했어요" else "현재 차량 내비의 안내 경로예요"
            } finally {
                reading = false
            }
        }
    }
    LaunchedEffect(gateway, connection) {
        if (connection == NavigationConnection.Ready && state.progress.stage == JourneyStage.ACTIVE && navigationSnapshot == null) {
            readNavigation()
        }
    }
    val actions = JourneyActions(
        onDraftChange = { draft ->
            if (state.progress.stage == JourneyStage.INPUT && !sending) {
                clearNavigationRead()
                update(state.copy(draft = draft.clearUnmatchedPlaces(), progress = JourneyProgress()))
            }
        },
        onCompare = {
            if (state.validation.request == null) update(state.copy(showErrors = true))
            else update(state.copy(progress = state.progress.compare(state.plans), showErrors = false))
        },
        onSelect = { id -> update(state.copy(progress = state.progress.select(id, state.plans))) },
        onReview = { update(state.copy(progress = state.progress.review(state.plans))) },
        onStart = {
            val selected = state.selectedPlan
            if (!sending && !reading && state.progress.stage == JourneyStage.REVIEW && selected?.isValid == true) {
                if (connection == NavigationConnection.Unsupported) {
                    update(state.copy(progress = state.progress.start(state.plans, System.currentTimeMillis())))
                } else {
                    val plan = state.draft.navigationPlan(selected)
                    if (plan == null) {
                        navigationMessage = "입력 화면에서 집과 들를 곳의 내비 장소를 선택해주세요"
                        navigationError = true
                    } else if (connection == NavigationConnection.Ready) {
                        val draft = state.draft
                        val selectedId = selected.id
                        sending = true
                        clearNavigationRead()
                        scope.launch {
                            try {
                                val result = gateway.requestRoute(plan)
                                if (state.progress.stage == JourneyStage.REVIEW &&
                                    state.progress.selectedPlanId == selectedId && state.draft == draft) {
                                    when (result) {
                                        is RouteRequestResult.Confirmed -> {
                                            navigationSnapshot = result.snapshot
                                            navigationMessage = "경유 순서와 최종 목적지가 내비에 반영됐어요"
                                            update(state.copy(progress = state.progress.start(state.plans, System.currentTimeMillis())))
                                        }
                                        RouteRequestResult.Unavailable -> {
                                            navigationMessage = "차량 내비 연결을 확인해주세요"
                                            navigationError = true
                                        }
                                        is RouteRequestResult.Rejected -> {
                                            navigationSnapshot = result.snapshot
                                            navigationMessage = result.reason
                                            navigationError = true
                                        }
                                    }
                                }
                            } finally {
                                sending = false
                            }
                        }
                    }
                }
            }
        },
        onCompleteVisit = { kind -> update(state.copy(progress = state.progress.completeVisit(state.plans, kind))) },
        onFinish = { update(state.copy(progress = state.progress.finish(state.plans, System.currentTimeMillis()))) },
        onBack = { if (!sending) update(state.copy(progress = state.progress.back())) },
        onEdit = { if (!sending) {
            clearNavigationRead()
            update(state.copy(progress = JourneyProgress(), showErrors = false))
        } },
        onCancel = { cancelRequested = true },
        onNewJourney = {
            clearNavigationRead()
            update(HomeUiState(freshDraft(state.draft)))
        },
        onChooseNavigationPlace = ::loadPlaces,
        onReadNavigation = ::readNavigation,
        onReconnectNavigation = {
            if (!sending && !reading) {
                dismissPicker()
                gatewayRevision += 1
            }
        },
    )
    BackHandler(enabled = state.progress.stage != JourneyStage.INPUT) {
        if (sending || reading) {
            navigationMessage = "차량 내비 응답을 확인하고 있어요"
        } else when (state.progress.stage) {
            JourneyStage.ACTIVE -> cancelRequested = true
            JourneyStage.COMPLETE -> actions.onNewJourney()
            JourneyStage.COMPARE, JourneyStage.REVIEW -> actions.onBack()
            JourneyStage.INPUT -> Unit
        }
    }
    HomeScreen(state = state, actions = actions, vehicle = VehicleNavigationUiState(
        connection, sending, reading, navigationMessage, navigationError, navigationSnapshot,
    ))
    picker?.let { current ->
        NavigationPlacePicker(
            state = current,
            onChoose = { point ->
                val role = current.role
                dismissPicker()
                clearNavigationRead()
                update(state.copy(draft = state.draft.chooseNavigationPlace(role, point),
                    progress = JourneyProgress(), showErrors = false))
            },
            onRefresh = { loadPlaces(current.role) },
            onDismiss = ::dismissPicker,
        )
    }
    if (cancelRequested) {
        AlertDialog(
            onDismissRequest = { cancelRequested = false },
            title = { Text("진행 중인 계획을 중단할까요?") },
            text = { Text("입력한 장소와 시간은 남겨둘게요. 차량 내비의 길안내는 계속돼요") },
            confirmButton = { TextButton(onClick = {
                cancelRequested = false
                clearNavigationRead()
                update(state.copy(progress = JourneyProgress(), showErrors = false))
            }) { Text("계획 중단") } },
            dismissButton = { TextButton(onClick = { cancelRequested = false }) { Text("계속 진행") } },
        )
    }
}
