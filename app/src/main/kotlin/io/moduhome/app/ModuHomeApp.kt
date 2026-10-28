package io.moduhome.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.RouteRequestResult
import io.moduhome.app.core.navigation.TripEstimateResult
import io.moduhome.app.core.transfer.ErrandProposalInbox
import io.moduhome.app.core.transfer.ProposalApprovalResult
import io.moduhome.app.core.transfer.ProposalReceiveResult
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.data.JourneyPreferences
import io.moduhome.app.data.navigation.PleosNavigationGateway
import io.moduhome.app.data.transfer.*
import io.moduhome.app.feature.transfer.ProposalActions
import io.moduhome.app.feature.transfer.ProposalContent
import io.moduhome.app.feature.transfer.ProposalUiState
import io.moduhome.app.feature.home.ProposalDraftResult
import io.moduhome.app.feature.home.withProposal
import io.moduhome.app.feature.home.proposal
import io.moduhome.app.feature.home.HomeScreen
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyActions
import io.moduhome.app.feature.home.JourneyDraft
import io.moduhome.app.feature.home.NavigationPlacePicker
import io.moduhome.app.feature.home.NavigationPlaceRole
import io.moduhome.app.feature.home.PlacePickerUiState
import io.moduhome.app.feature.home.VehicleNavigationUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun ModuHomeApp(incomingProposal: String? = null, onProposalConsumed: () -> Unit = {}) {
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
    var estimating by remember(gateway) { mutableStateOf(false) }
    var tripResult by remember(gateway) { mutableStateOf<TripEstimateResult?>(null) }
    var tripNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
    val proposalPreferences = remember(context) { ProposalInboxPreferences(context) }
    val restoredProposals = remember(proposalPreferences) { proposalPreferences.load(System.currentTimeMillis()) }
    var inbox by remember { mutableStateOf((restoredProposals as? ProposalInboxLoadResult.Loaded)?.inbox ?: ErrandProposalInbox.empty()) }
    var proposalPanelOpen by remember { mutableStateOf(false) }
    var proposalInput by remember { mutableStateOf("") }
    var proposalMessage by remember { mutableStateOf((restoredProposals as? ProposalInboxLoadResult.Failed)?.reason) }
    var exportLink by remember { mutableStateOf<String?>(null) }
    var exportName by remember { mutableStateOf<String?>(null) }
    var proposalQr by remember { mutableStateOf<ImageBitmap?>(null) }
    var generatingQr by remember { mutableStateOf(false) }
    var readingQr by remember { mutableStateOf(false) }
    fun update(next: HomeUiState) {
        state = next
        preferences.save(next)
    }
    fun clearNavigationRead() {
        navigationMessage = null
        navigationError = false
        navigationSnapshot = null
        tripResult = null
    }
    fun saveInbox(next: ErrandProposalInbox) {
        proposalPreferences.save(next)
        inbox = next
    }
    fun receiveProposal(link: String) {
        proposalPanelOpen = true
        when (val decoded = ProposalLinkCodec.decode(link.trim(), System.currentTimeMillis())) {
            is ProposalDecodeResult.Invalid -> proposalMessage = decoded.reason
            is ProposalDecodeResult.Valid -> when (val received = inbox.receive(decoded.proposal, System.currentTimeMillis())) {
                is ProposalReceiveResult.Pending -> {
                    saveInbox(received.inbox)
                    proposalInput = ""
                    proposalMessage = "제안을 받았어요. 장소와 시간을 확인해주세요"
                }
                ProposalReceiveResult.Duplicate -> proposalMessage = "이미 받은 제안이에요"
                is ProposalReceiveResult.Rejected -> proposalMessage = received.reason
            }
        }
    }
    LaunchedEffect(incomingProposal) {
        if (incomingProposal != null) {
            receiveProposal(incomingProposal)
            onProposalConsumed()
        }
    }
    LaunchedEffect(exportLink) {
        proposalQr = null
        val link = exportLink ?: return@LaunchedEffect
        generatingQr = true
        try {
            proposalQr = withContext(Dispatchers.Default) { ProposalQrCode.encode(link)?.asImageBitmap() }
            if (proposalQr == null) proposalMessage = "내용이 길어 QR을 만들지 못했어요. 링크로 전달해주세요"
        } finally { generatingQr = false }
    }
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !readingQr) {
            readingQr = true
            scope.launch {
                try {
                    val link = readProposalQr(context.contentResolver, uri)
                    if (link == null) proposalMessage = "이미지에서 QR을 읽지 못했어요. 제안 링크를 붙여넣어주세요"
                    else receiveProposal(link)
                } finally { readingQr = false }
            }
        }
    }
    LaunchedEffect(tripResult) {
        tripNow = System.currentTimeMillis()
        if (tripResult is TripEstimateResult.Success) {
            while (true) {
                delay(30_000)
                tripNow = System.currentTimeMillis()
            }
        }
    }
    fun dismissPicker() {
        pickerRequest += 1
        pickerJob?.cancel()
        picker = null
    }
    fun loadPlaces(role: NavigationPlaceRole) {
        if (state.progress.stage != JourneyStage.INPUT || sending || estimating) return
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
        if (reading || sending || estimating || connection != NavigationConnection.Ready) return
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
            if (state.progress.stage == JourneyStage.INPUT && !sending && !estimating) {
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
            if (!sending && !reading && !estimating && state.progress.stage == JourneyStage.REVIEW && selected?.isValid == true) {
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
            if (!sending && !reading && !estimating) {
                dismissPicker()
                gatewayRevision += 1
            }
        },
        onOpenProposals = { proposalPanelOpen = true },
        onEstimateTrip = { role ->
            val point = state.draft.navigationPoint(role)
            if (point != null && !sending && !reading && !estimating && connection == NavigationConnection.Ready) {
                estimating = true
                tripResult = null
                scope.launch {
                    try { tripResult = gateway.estimateTrip(point) }
                    finally { estimating = false }
                }
            }
        },
    )
    BackHandler(enabled = state.progress.stage != JourneyStage.INPUT) {
        if (sending || reading || estimating) {
            navigationMessage = "차량 내비 응답을 확인하고 있어요"
        } else when (state.progress.stage) {
            JourneyStage.ACTIVE -> cancelRequested = true
            JourneyStage.COMPLETE -> actions.onNewJourney()
            JourneyStage.COMPARE, JourneyStage.REVIEW -> actions.onBack()
            JourneyStage.INPUT -> Unit
        }
    }
    HomeScreen(state = state, actions = actions, vehicle = VehicleNavigationUiState(
        connection, sending, reading || estimating, navigationMessage, navigationError, navigationSnapshot,
        tripResult = tripResult, tripNow = tripNow,
    ))
    if (proposalPanelOpen) {
        ProposalContent(
            state = ProposalUiState(inbox, proposalInput, proposalMessage,
                canApply = state.progress.stage == JourneyStage.INPUT && !sending && !reading && !estimating,
                exportLink = exportLink, exportName = exportName, qr = proposalQr, qrBusy = generatingQr || readingQr,
                pickupAvailable = state.draft.navigationPoint(NavigationPlaceRole.PICKUP) != null,
                groceriesAvailable = state.draft.navigationPoint(NavigationPlaceRole.GROCERIES) != null),
            actions = ProposalActions(
                onInput = { proposalInput = it.take(ProposalLinkCodec.MAX_LINK_LENGTH + 1) },
                onReceive = { receiveProposal(proposalInput) },
                onPaste = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    proposalInput = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(ProposalLinkCodec.MAX_LINK_LENGTH + 1)
                },
                onReadQr = { qrPicker.launch("image/*") },
                onApprove = { id, revision ->
                    if (state.progress.stage == JourneyStage.INPUT && !sending && !reading && !estimating) {
                        when (val approval = inbox.approve(id, revision, System.currentTimeMillis())) {
                            is ProposalApprovalResult.Rejected -> proposalMessage = approval.reason
                            is ProposalApprovalResult.Approved -> when (val applied = state.draft.withProposal(approval.proposal)) {
                                is ProposalDraftResult.Invalid -> proposalMessage = applied.reason
                                is ProposalDraftResult.Applied -> {
                                    clearNavigationRead()
                                    update(state.copy(draft = applied.draft, progress = JourneyProgress(), showErrors = false))
                                    saveInbox(approval.inbox)
                                    proposalMessage = "이번 계획에 반영했어요. 계획 비교에서 순서를 확인하세요"
                                }
                            }
                        }
                    }
                },
                onDecline = { id, revision -> saveInbox(inbox.decline(id, revision)) },
                onExport = { kind ->
                    val proposal = state.draft.proposal(kind, UUID.randomUUID().toString(), System.currentTimeMillis())
                    if (proposal == null) proposalMessage = "연결된 장소와 체류·도보·마감 시간을 확인해주세요"
                    else {
                        exportName = proposal.place.name
                        exportLink = ProposalLinkCodec.encode(proposal)
                        proposalMessage = "제안을 만들었어요. QR이나 링크로 전달해주세요"
                    }
                },
                onCopy = {
                    exportLink?.let { link ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("볼일 제안", link))
                        proposalMessage = "제안 링크를 복사했어요"
                    }
                },
                onShare = {
                    exportLink?.let { link ->
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                        try { context.startActivity(Intent.createChooser(send, "볼일 제안 전달").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: android.content.ActivityNotFoundException) { proposalMessage = "공유할 앱이 없어요. 링크 복사를 이용해주세요" }
                    }
                },
                onDismiss = { proposalPanelOpen = false },
            ),
        )
    }
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
