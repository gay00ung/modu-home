package io.moduhome.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import io.moduhome.app.core.navigation.GuidanceEstimateResult
import io.moduhome.app.core.navigation.RouteChangeApproval
import io.moduhome.app.core.navigation.remainingAfter
import io.moduhome.app.feature.home.NavigationResumeConfirmation
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.RouteRequestResult
import io.moduhome.app.core.navigation.TripEstimateResult
import io.moduhome.app.core.navigation.VehicleLocationResult
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.core.voice.*
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposalInbox
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.ProposalReviewStatus
import io.moduhome.app.core.transfer.ProposalApprovalResult
import io.moduhome.app.core.transfer.ProposalReceiveResult
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.data.JourneyPreferences
import io.moduhome.app.data.shared.SharedJourneyPreferences
import io.moduhome.app.feature.shared.SharedJourneyFlow
import io.moduhome.app.feature.shared.SharedJourneyFlowState
import io.moduhome.app.feature.shared.SharedProposalDialog
import io.moduhome.app.feature.shared.SharedProposalReview
import io.moduhome.app.feature.shared.SharedProposalReviewResult
import io.moduhome.app.feature.shared.SharedProposalApplyResult
import io.moduhome.app.data.navigation.PleosNavigationGateway
import io.moduhome.app.data.routing.OsrmRouteEstimateGateway
import io.moduhome.app.data.vehicle.PleosDrivingRestrictions
import io.moduhome.app.data.voice.PleosVoiceInputGateway
import io.moduhome.app.feature.vehicle.VehicleJourneyScreen
import io.moduhome.app.feature.vehicle.VehiclePlacePicker
import io.moduhome.app.feature.vehicle.VehicleProposalDialog
import io.moduhome.app.feature.vehicle.VehicleConfirmationDialog
import io.moduhome.app.feature.vehicle.VehicleVoiceDialog
import io.moduhome.app.feature.vehicle.RestrictedJourneyScreen
import io.moduhome.app.data.transfer.*
import io.moduhome.app.feature.transfer.ProposalActions
import io.moduhome.app.feature.transfer.ProposalContent
import io.moduhome.app.feature.transfer.ProposalUiState
import io.moduhome.app.feature.home.ProposalDraftResult
import io.moduhome.app.feature.home.withProposal
import io.moduhome.app.feature.home.proposal
import io.moduhome.app.feature.home.loadRoutedJourney
import io.moduhome.app.feature.home.RoutedJourneyLoadResult
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
    val uiContext = LocalContext.current
    val context = uiContext.applicationContext
    val isVehicle = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) }
    val restrictions = remember(uiContext) { PleosDrivingRestrictions(uiContext) }
    val interaction by restrictions.state.collectAsStateWithLifecycle()
    val editingAllowed = interaction.editingAllowed
    val voice = remember(context) { PleosVoiceInputGateway(context) }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var detailsOpen by remember { mutableStateOf(false) }
    var voiceDialogOpen by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    var voiceCommand by remember { mutableStateOf<JourneyVoiceCommand?>(null) }
    var voiceRequest by remember { mutableLongStateOf(0L) }
    fun dismissVoice() {
        voiceRequest += 1
        voice.stop()
        voiceDialogOpen = false
        voiceCommand = null
        voiceMessage = null
    }
    DisposableEffect(restrictions, voice, lifecycleOwner) {
        restrictions.connect()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) dismissVoice()
            if (event == Lifecycle.Event.ON_RESUME) restrictions.connect()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voice.close()
            restrictions.close()
        }
    }
    val preferences = remember(context) { JourneyPreferences(context) }
    val sharedPreferences = remember(context) { SharedJourneyPreferences(context) }
    var sharedMode by remember(sharedPreferences) { mutableStateOf(isVehicle && sharedPreferences.isSelectedMode()) }
    var sharedState by remember(sharedPreferences) {
        mutableStateOf(SharedJourneyFlowState(session = sharedPreferences.load()))
    }
    fun updateShared(next: SharedJourneyFlowState): Boolean {
        if (next.session != sharedState.session && !sharedPreferences.save(next.session)) {
            sharedState = sharedState.copy(message = "진행 상황을 저장하지 못했어요. 다시 시도해주세요", error = true)
            return false
        }
        sharedState = next
        return true
    }
    val scope = rememberCoroutineScope()
    var gatewayRevision by remember { mutableIntStateOf(0) }
    val gateway = remember(context, restrictions, gatewayRevision) {
        PleosNavigationGateway(context) { restrictions.state.value.editingAllowed }
    }
    val routeGateway = remember {
        BuildConfig.ROUTING_BASE_URL.takeIf(String::isNotBlank)?.let {
            runCatching { OsrmRouteEstimateGateway(it, allowLocalHttp = BuildConfig.DEBUG) }.getOrNull()
        }
    }
    val connection by gateway.connection.collectAsStateWithLifecycle()
    val routeRevision by gateway.routeRevision.collectAsStateWithLifecycle()
    DisposableEffect(gateway) {
        gateway.connect()
        onDispose { gateway.close() }
    }
    var sending by remember(gateway) { mutableStateOf(false) }
    var reading by remember(gateway) { mutableStateOf(false) }
    var estimating by remember(gateway) { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var tripResult by remember(gateway) { mutableStateOf<TripEstimateResult?>(null) }
    var guidanceResult by remember(gateway) { mutableStateOf<GuidanceEstimateResult?>(null) }
    var guidanceRefreshing by remember(gateway) { mutableStateOf(false) }
    var resumeConfirmation by remember(gateway) { mutableStateOf<NavigationResumeConfirmation?>(null) }
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
    var sharedProposalPanelOpen by remember { mutableStateOf(false) }
    var selectedSharedProposal by remember { mutableStateOf<ErrandProposal?>(null) }
    var sharedProposalReview by remember { mutableStateOf<SharedProposalReview?>(null) }
    var sharedProposalInteraction by remember { mutableStateOf<DrivingInteraction?>(null) }
    var sharedProposalMessage by remember { mutableStateOf<String?>(null) }
    var sharedProposalNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun canReviewSharedProposal(): Boolean = sharedMode && restrictions.state.value.editingAllowed &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        sharedState.session.progress.stage == JourneyStage.INPUT && !sharedState.busy && !sharedState.dialogOpen
    fun dismissSharedProposals() {
        sharedProposalPanelOpen = false
        selectedSharedProposal = null
        sharedProposalReview = null
        sharedProposalInteraction = null
    }
    fun selectSharedProposal(id: String, revision: Int) {
        if (!canReviewSharedProposal()) return
        selectedSharedProposal = inbox.entries.firstOrNull { it.proposal.id == id }?.proposal
        sharedProposalNow = System.currentTimeMillis()
        sharedProposalInteraction = restrictions.state.value
        when (val result = sharedState.session.reviewProposal(inbox, id, revision, sharedProposalNow)) {
            is SharedProposalReviewResult.Ready -> {
                sharedProposalReview = result.review
                sharedProposalMessage = null
            }
            is SharedProposalReviewResult.Rejected -> {
                sharedProposalReview = null
                sharedProposalMessage = result.reason
            }
        }
    }
    fun openSharedProposals() {
        if (!canReviewSharedProposal()) return
        sharedProposalPanelOpen = true
        sharedProposalMessage = null
        val first = inbox.entries.firstOrNull { it.status == ProposalReviewStatus.PENDING }
        if (first != null) selectSharedProposal(first.proposal.id, first.proposal.revision)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) dismissSharedProposals()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
        proposalPanelOpen = editingAllowed && !sharedMode
        when (val decoded = ProposalLinkCodec.decode(link.trim(), System.currentTimeMillis())) {
            is ProposalDecodeResult.Invalid -> {
                proposalMessage = decoded.reason
                if (sharedMode) sharedState = sharedState.copy(message = decoded.reason, error = true)
            }
            is ProposalDecodeResult.Valid -> when (val received = inbox.receive(decoded.proposal, System.currentTimeMillis())) {
                is ProposalReceiveResult.Pending -> {
                    saveInbox(received.inbox)
                    proposalInput = ""
                    proposalMessage = "제안을 받았어요. 장소와 시간을 확인해주세요"
                    if (sharedMode) sharedState = sharedState.copy(
                        message = if (sharedState.session.progress.stage == JourneyStage.INPUT)
                            "볼일 제안을 받았어요. 받은 볼일에서 확인해주세요"
                        else "볼일 제안을 보관했어요. 다음 계획에서 확인할 수 있어요", error = false,
                    )
                }
                ProposalReceiveResult.Duplicate -> proposalMessage = "이미 받은 제안이에요"
                is ProposalReceiveResult.Rejected -> proposalMessage = received.reason
            }
        }
    }
    LaunchedEffect(sharedState.session.acceptedProposals, inbox) {
        val restored = sharedState.session.reconcileProposalInbox(inbox)
        if (restored != inbox) saveInbox(restored)
    }
    LaunchedEffect(sharedProposalPanelOpen, sharedMode, interaction, sharedState.session, inbox) {
        if (!sharedProposalPanelOpen) return@LaunchedEffect
        if (!canReviewSharedProposal() || (sharedProposalInteraction != null && sharedProposalInteraction != interaction)) {
            dismissSharedProposals()
            return@LaunchedEffect
        }
        val selected = selectedSharedProposal
        val entry = inbox.entries.firstOrNull { it.proposal.id == selected?.id && it.status == ProposalReviewStatus.PENDING }
        if (selected != null && (entry?.proposal != selected || sharedProposalReview?.session?.let { it != sharedState.session } == true)) {
            // A new version receives a fresh preview; the button below remains bound to its rendered review.
            if (entry != null) selectSharedProposal(entry.proposal.id, entry.proposal.revision)
            else {
                selectedSharedProposal = null
                sharedProposalReview = null
                sharedProposalMessage = "이 제안은 이미 검토했어요"
            }
        }
    }
    LaunchedEffect(sharedProposalPanelOpen) {
        if (sharedProposalPanelOpen) {
            while (true) {
                sharedProposalNow = System.currentTimeMillis()
                delay(1_000L)
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
    LaunchedEffect(tripResult, guidanceResult) {
        tripNow = System.currentTimeMillis()
        if (tripResult is TripEstimateResult.Success || guidanceResult is GuidanceEstimateResult.Success) {
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
        if (!editingAllowed || state.progress.stage != JourneyStage.INPUT || sending || estimating || planning) return
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
        if (reading || sending || estimating || planning || connection != NavigationConnection.Ready) return
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
    LaunchedEffect(gateway, connection, sharedMode) {
        if (!sharedMode && connection == NavigationConnection.Ready && state.progress.stage == JourneyStage.ACTIVE && navigationSnapshot == null) {
            readNavigation()
        }
    }
    val guidancePlan = state.selectedPlan?.let(state.draft::navigationPlan)?.remainingAfter(state.progress.completedVisits)
    LaunchedEffect(gateway, connection, routeRevision, state.progress.stage, guidancePlan, lifecycleOwner,
        reading, sending, resumeConfirmation, sharedMode) {
        guidanceResult = null
        if (sharedMode || connection != NavigationConnection.Ready || state.progress.stage != JourneyStage.ACTIVE || guidancePlan == null ||
            reading || sending || resumeConfirmation != null) {
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                navigationSnapshot = null
                while (true) {
                    guidanceRefreshing = true
                    val result = gateway.currentGuidance(guidancePlan)
                    guidanceResult = result
                    navigationSnapshot = result.observedRoute
                    guidanceRefreshing = false
                    delay(30_000)
                }
            } finally {
                guidanceRefreshing = false
                guidanceResult = null
            }
        }
    }
    LaunchedEffect(routeRevision, editingAllowed, state.progress) {
        val pending = resumeConfirmation
        if (pending != null && (!editingAllowed || pending.progress != state.progress ||
                pending.approval.routeRevision != routeRevision)) {
            resumeConfirmation = null
            navigationMessage = "안내 상태가 바뀌었어요. 남은 순서를 다시 확인해주세요"
            navigationError = true
        }
    }

    fun prepareNavigationResume() {
        if (!restrictions.state.value.editingAllowed || sending || reading || estimating || planning ||
            state.progress.stage != JourneyStage.ACTIVE || connection != NavigationConnection.Ready) return
        val progress = state.progress
        val remaining = state.selectedPlan?.let(state.draft::navigationPlan)?.remainingAfter(progress.completedVisits) ?: return
        reading = true
        scope.launch {
            try {
                val before = gateway.routeRevision.value
                val snapshot = gateway.currentRoute()
                val approval = RouteChangeApproval.fromObservation(snapshot, before, gateway.routeRevision.value)
                if (state.progress == progress && restrictions.state.value.editingAllowed) {
                    navigationSnapshot = snapshot
                    if (approval == null) {
                        navigationMessage = "현재 내비 안내를 확인하지 못했어요. 다시 시도해주세요"
                        navigationError = true
                    } else resumeConfirmation = NavigationResumeConfirmation(remaining, approval, progress)
                }
            } finally { reading = false }
        }
    }
    val roadComparisonAvailable = routeGateway != null && connection == NavigationConnection.Ready &&
        state.draft.navigationPoint(NavigationPlaceRole.HOME) != null &&
        (!state.draft.pickup.enabled || state.draft.navigationPoint(NavigationPlaceRole.PICKUP) != null) &&
        (!state.draft.groceries.enabled || state.draft.navigationPoint(NavigationPlaceRole.GROCERIES) != null)

    fun compareRoadRoutes() {
        val routes = routeGateway ?: return
        if (!editingAllowed || planning || sending || reading || estimating || state.progress.stage != JourneyStage.INPUT) return
        val previous = state.draft
        val now = LocalDateTime.now()
        val draft = previous.copy(origin = "현재 차량 위치", departure = now.format(DateTimeFormatter.ofPattern("HH:mm")),
            dateEpochDay = now.toLocalDate().toEpochDay())
        val validation = draft.validate(requireTravelTimes = false)
        if (validation.request == null) {
            update(state.copy(showErrors = true))
            navigationMessage = validation.errors.values.firstOrNull()
            navigationError = true
            return
        }
        planning = true
        clearNavigationRead()
        navigationMessage = "현재 위치에서 두 방문 순서의 도로 경로를 확인하고 있어요"
        scope.launch {
            try {
                val location = gateway.currentLocation()
                val result = when (location) {
                    is VehicleLocationResult.Available -> loadRoutedJourney(draft, location.location.point, routes)
                    is VehicleLocationResult.Failed -> RoutedJourneyLoadResult.Failed(location.reason)
                    VehicleLocationResult.Unsupported -> RoutedJourneyLoadResult.Failed("차량에서 현재 위치를 확인해주세요")
                }
                if (state.draft == previous && state.progress.stage == JourneyStage.INPUT) {
                    when (result) {
                        is RoutedJourneyLoadResult.Success -> {
                            val next = HomeUiState(draft = draft, routing = result.estimates)
                            update(next.copy(progress = next.progress.compare(next.plans)))
                            navigationMessage = "방문 순서별 도로 경로를 확인했어요"
                        }
                        is RoutedJourneyLoadResult.Failed -> {
                            navigationMessage = result.reason
                            navigationError = true
                        }
                    }
                }
            } finally { planning = false }
        }
    }
    LaunchedEffect(editingAllowed, interaction.voiceTranscriptionAllowed) {
        if (!editingAllowed) {
            detailsOpen = false
            proposalPanelOpen = false
            cancelRequested = false
            dismissPicker()
            dismissVoice()
        }
        if (!interaction.voiceTranscriptionAllowed) {
            dismissVoice()
        }
    }
    LaunchedEffect(state.progress.stage) {
        if (state.progress.stage != JourneyStage.INPUT) detailsOpen = false
    }
    val actions = JourneyActions(
        onDraftChange = { draft ->
            if (editingAllowed && state.progress.stage == JourneyStage.INPUT && !sending && !estimating && !planning) {
                clearNavigationRead()
                val next = draft.clearUnmatchedPlaces()
                update(state.copy(draft = next, progress = JourneyProgress(), routing = state.routing?.takeIf { it.matches(next) }))
            }
        },
        onCompare = {
            if (!editingAllowed) Unit
            else if (isVehicle && !roadComparisonAvailable) {
                navigationMessage = "집과 들를 곳을 선택하고 차량 내비 연결을 확인해주세요"
                navigationError = true
            }
            else if (roadComparisonAvailable) compareRoadRoutes()
            else if (state.validation.request == null) update(state.copy(showErrors = true))
            else update(state.copy(progress = state.progress.compare(state.plans), showErrors = false))
        },
        onSelect = { id -> if (editingAllowed) update(state.copy(progress = state.progress.select(id, state.plans))) },
        onReview = { if (editingAllowed) update(state.copy(progress = state.progress.review(state.plans))) },
        onStart = {
            val selected = state.selectedPlan
            if (editingAllowed && !sending && !reading && !estimating && !planning && state.progress.stage == JourneyStage.REVIEW && selected?.isValid == true) {
                if (state.routing?.isFresh(System.currentTimeMillis()) == false) {
                    update(state.copy(progress = JourneyProgress()))
                    navigationMessage = "조회 후 5분이 지났어요. 출발 전에 경로를 다시 비교해주세요"
                    navigationError = true
                } else if (connection == NavigationConnection.Unsupported) {
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
        onCompleteVisit = { kind -> if (editingAllowed) update(state.copy(progress = state.progress.completeVisit(state.plans, kind))) },
        onFinish = { if (editingAllowed) update(state.copy(progress = state.progress.finish(state.plans, System.currentTimeMillis()))) },
        onBack = { if (editingAllowed && !sending) update(state.copy(progress = state.progress.back())) },
        onEdit = { if (editingAllowed && !sending) {
            clearNavigationRead()
            update(state.copy(progress = JourneyProgress(), showErrors = false))
        } },
        onCancel = { if (editingAllowed) cancelRequested = true },
        onNewJourney = {
            if (editingAllowed) {
            clearNavigationRead()
            update(HomeUiState(freshDraft(state.draft)))
            }
        },
        onChooseNavigationPlace = ::loadPlaces,
        onReadNavigation = ::readNavigation,
        onResumeNavigation = ::prepareNavigationResume,
        onReconnectNavigation = {
            if (editingAllowed && !sending && !reading && !estimating && !planning) {
                dismissPicker()
                gatewayRevision += 1
            }
        },
        onOpenProposals = { if (editingAllowed) proposalPanelOpen = true },
        onEstimateTrip = { role ->
            val point = state.draft.navigationPoint(role)
            if (editingAllowed && point != null && !sending && !reading && !estimating && !planning && connection == NavigationConnection.Ready) {
                estimating = true
                tripResult = null
                scope.launch {
                    try { tripResult = gateway.estimateTrip(point) }
                    finally { estimating = false }
                }
            }
        },
    )
    BackHandler(enabled = !sharedMode && state.progress.stage != JourneyStage.INPUT) {
        if (!editingAllowed) {
            navigationMessage = "차량의 입력 제한이 해제된 뒤 계획을 바꿀 수 있어요"
        } else if (sending || reading || estimating || planning) {
            navigationMessage = "차량 내비 응답을 확인하고 있어요"
        } else when (state.progress.stage) {
            JourneyStage.ACTIVE -> cancelRequested = true
            JourneyStage.COMPLETE -> actions.onNewJourney()
            JourneyStage.COMPARE, JourneyStage.REVIEW -> actions.onBack()
            JourneyStage.INPUT -> Unit
        }
    }
    val vehicleState = VehicleNavigationUiState(
        connection, sending, reading || estimating || planning, navigationMessage, navigationError, navigationSnapshot,
        tripResult = tripResult, tripNow = tripNow, roadComparisonAvailable = roadComparisonAvailable, planning = planning,
        guidanceResult = guidanceResult, guidanceRefreshing = guidanceRefreshing,
    )
    if (isVehicle && sharedMode) {
        SharedJourneyFlow(
            state = sharedState,
            onStateChange = ::updateShared,
            readState = { sharedState },
            gateway = gateway,
            routeGateway = routeGateway,
            interaction = interaction,
            pendingProposalCount = inbox.entries.count { it.status == ProposalReviewStatus.PENDING },
            proposalPanelOpen = sharedProposalPanelOpen,
            onOpenProposals = ::openSharedProposals,
            onReconnect = {
                if (restrictions.state.value.editingAllowed && !sharedState.busy) {
                    sharedState = sharedState.copy(snapshot = null, observedRouteRevision = null,
                        confirmation = null, message = "차량 내비에 다시 연결하고 있어요", error = false)
                    gatewayRevision += 1
                }
            },
            onLeave = {
                if (restrictions.state.value.editingAllowed &&
                    sharedState.session.progress.stage in setOf(JourneyStage.INPUT, JourneyStage.COMPLETE)) {
                    if (sharedPreferences.selectMode(false)) sharedMode = false
                    else sharedState = sharedState.copy(message = "화면 선택을 저장하지 못했어요", error = true)
                }
            },
        )
    } else if (isVehicle && !editingAllowed) {
        RestrictedJourneyScreen(state = state, interaction = interaction)
    } else if (isVehicle && !detailsOpen) {
        VehicleJourneyScreen(state, actions, vehicleState, editingAllowed,
            restrictionMessage = when (val status = interaction) {
                is DrivingInteraction.Unknown -> status.reason
                is DrivingInteraction.Known -> if (status.editingAllowed) null else "계획 변경은 정차 후에 할 수 있어요"
                DrivingInteraction.NotAutomotive -> null
            },
            onOpenDetails = { if (editingAllowed) detailsOpen = true },
            onOpenShared = {
                if (restrictions.state.value.editingAllowed && !sending && !reading && !estimating && !planning &&
                    state.progress.stage in setOf(JourneyStage.INPUT, JourneyStage.COMPLETE)) {
                    if (sharedPreferences.selectMode(true)) {
                        dismissPicker()
                        dismissVoice()
                        proposalPanelOpen = false
                        detailsOpen = false
                        resumeConfirmation = null
                        sharedMode = true
                    } else {
                        navigationMessage = "화면 선택을 저장하지 못했어요"
                        navigationError = true
                    }
                }
            },
            voiceAvailable = BuildConfig.ENABLE_VEHICLE_VOICE && interaction.voiceTranscriptionAllowed &&
                voiceState !is VoiceInputState.Failed && voiceState != VoiceInputState.Stopping,
            onVoice = {
                if (BuildConfig.ENABLE_VEHICLE_VOICE && editingAllowed && interaction.voiceTranscriptionAllowed &&
                    state.progress.stage == JourneyStage.INPUT && voiceState == VoiceInputState.Idle) {
                    voiceRequest += 1
                    val request = voiceRequest
                    voiceDialogOpen = true
                    voiceCommand = null
                    voiceMessage = null
                    scope.launch {
                        val result = voice.recognize()
                        if (request == voiceRequest && voiceDialogOpen && interaction.editingAllowed && interaction.voiceTranscriptionAllowed) {
                            when (result) {
                                is VoiceRecognitionResult.Recognized -> when (val parsed = KoreanJourneyVoiceParser.parse(result.text)) {
                                    is VoiceCommandParseResult.Supported -> voiceCommand = parsed.command
                                    is VoiceCommandParseResult.Unsupported -> voiceMessage = parsed.reason
                                }
                                is VoiceRecognitionResult.Unavailable -> voiceMessage = result.reason
                                VoiceRecognitionResult.NoSpeech -> voiceMessage = "말씀을 듣지 못했어요. 버튼을 눌러 다시 시작해주세요"
                                VoiceRecognitionResult.Stopped -> voiceMessage = "음성 입력을 중지했어요"
                                VoiceRecognitionResult.Busy -> voiceMessage = "이미 음성을 듣고 있어요"
                            }
                        }
                    }
                }
            },
        )
    } else {
        Column {
            if (isVehicle) Button(onClick = { detailsOpen = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)) {
                Text("간단한 차량 화면으로 돌아가기")
            }
            HomeScreen(state = state, actions = actions, vehicle = vehicleState, modifier = Modifier.weight(1f))
        }
        BackHandler(enabled = isVehicle && detailsOpen) { detailsOpen = false }
    }
    if (sharedMode && sharedProposalPanelOpen && editingAllowed) {
        val visibleReview = sharedProposalReview
        val visibleInteraction = sharedProposalInteraction
        val selected = selectedSharedProposal
        val unavailable = selected?.unavailableReason(sharedProposalNow)
        val currentEntry = inbox.entries.firstOrNull { it.proposal.id == selected?.id }
        val canApply = sharedState.session.progress.stage == JourneyStage.INPUT && !sharedState.busy &&
            !sharedState.dialogOpen && visibleReview != null && unavailable == null &&
            visibleReview.session == sharedState.session && visibleReview.proposal == selected &&
            currentEntry?.proposal == selected && currentEntry?.status == ProposalReviewStatus.PENDING &&
            visibleInteraction == interaction
        SharedProposalDialog(
            inbox = inbox,
            selectedProposal = selected,
            review = visibleReview,
            message = unavailable ?: sharedProposalMessage,
            canApply = canApply,
            onSelect = ::selectSharedProposal,
            onApprove = {
                if (canReviewSharedProposal() && visibleReview != null && sharedProposalReview == visibleReview &&
                    visibleInteraction == restrictions.state.value) {
                    when (val applied = sharedState.session.approveProposal(visibleReview, inbox, System.currentTimeMillis())) {
                        is SharedProposalApplyResult.Rejected -> sharedProposalMessage = applied.reason
                        is SharedProposalApplyResult.Applied -> {
                            val saved = updateShared(SharedJourneyFlowState(
                                session = applied.session,
                                operationRevision = sharedState.operationRevision + 1,
                                message = "받은 볼일을 반영했어요. 계획을 비교해 순서를 골라주세요",
                            ))
                            if (saved) {
                                saveInbox(applied.inbox)
                                dismissSharedProposals()
                            } else sharedProposalMessage = "볼일을 저장하지 못했어요. 다시 시도해주세요"
                        }
                    }
                }
            },
            onDecline = { id, revision ->
                if (canReviewSharedProposal()) {
                    saveInbox(inbox.decline(id, revision))
                    selectedSharedProposal = null
                    sharedProposalReview = null
                    sharedProposalMessage = null
                    val next = inbox.entries.firstOrNull { it.status == ProposalReviewStatus.PENDING }
                    if (next != null) selectSharedProposal(next.proposal.id, next.proposal.revision)
                }
            },
            onDismiss = ::dismissSharedProposals,
        )
    }
    if (!sharedMode && voiceDialogOpen && editingAllowed && interaction.voiceTranscriptionAllowed) {
        VehicleVoiceDialog(
            state = voiceState, message = voiceMessage, command = voiceCommand,
            onDismiss = ::dismissVoice,
            onConfirm = {
                val command = voiceCommand
                if (editingAllowed && state.progress.stage == JourneyStage.INPUT && command != null) {
                    val draft = state.draft
                    val next = when (command) {
                        is JourneyVoiceCommand.SetVisitEnabled -> when (command.kind) {
                            VisitKind.PICKUP -> draft.copy(pickup = draft.pickup.copy(enabled = command.enabled))
                            VisitKind.GROCERIES -> draft.copy(groceries = draft.groceries.copy(enabled = command.enabled))
                        }
                        is JourneyVoiceCommand.SetAdditionalBudget -> draft.copy(budget = command.minutes)
                        is JourneyVoiceCommand.SetPickupReady -> draft.copy(pickup = draft.pickup.copy(ready = command.ready))
                    }
                    actions.onDraftChange(next)
                }
                dismissVoice()
            },
        )
    }

    if (!sharedMode && proposalPanelOpen && editingAllowed) {
        val proposalState = ProposalUiState(inbox, proposalInput, proposalMessage,
                canApply = editingAllowed && state.progress.stage == JourneyStage.INPUT && !sending && !reading && !estimating && !planning,
                exportLink = exportLink, exportName = exportName, qr = proposalQr, qrBusy = generatingQr || readingQr,
                pickupAvailable = state.draft.navigationPoint(NavigationPlaceRole.PICKUP) != null,
                groceriesAvailable = state.draft.navigationPoint(NavigationPlaceRole.GROCERIES) != null)
        val proposalActions = ProposalActions(
                onInput = { proposalInput = it.take(ProposalLinkCodec.MAX_LINK_LENGTH + 1) },
                onReceive = { receiveProposal(proposalInput) },
                onPaste = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    proposalInput = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(ProposalLinkCodec.MAX_LINK_LENGTH + 1)
                },
                onReadQr = { qrPicker.launch("image/*") },
                onApprove = { id, revision ->
                    if (editingAllowed && state.progress.stage == JourneyStage.INPUT && !sending && !reading && !estimating && !planning) {
                        when (val approval = inbox.approve(id, revision, System.currentTimeMillis())) {
                            is ProposalApprovalResult.Rejected -> proposalMessage = approval.reason
                            is ProposalApprovalResult.Approved -> when (val applied = state.draft.withProposal(approval.proposal)) {
                                is ProposalDraftResult.Invalid -> proposalMessage = applied.reason
                                is ProposalDraftResult.Applied -> {
                                    clearNavigationRead()
                                    update(state.copy(draft = applied.draft, progress = JourneyProgress(), showErrors = false,
                                        routing = state.routing?.takeIf { it.matches(applied.draft) }))
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
        )
        if (isVehicle) VehicleProposalDialog(proposalState, proposalActions)
        else ProposalContent(proposalState, proposalActions)
    }
    picker?.takeIf { editingAllowed && !sharedMode }?.let { current ->
        val choose: (io.moduhome.app.core.navigation.NavigationPoint) -> Unit = { point ->
            if (editingAllowed && state.progress.stage == JourneyStage.INPUT && !sending && !planning) {
                clearNavigationRead()
                update(state.copy(draft = state.draft.chooseNavigationPlace(current.role, point),
                    progress = JourneyProgress(), routing = null, showErrors = false))
                dismissPicker()
            }
        }
        if (isVehicle) VehiclePlacePicker(current, choose, { loadPlaces(current.role) }, ::dismissPicker)
        else NavigationPlacePicker(current, choose, { loadPlaces(current.role) }, ::dismissPicker)
    }

    if (!sharedMode && cancelRequested && editingAllowed) {
        val cancel: () -> Unit = {
            cancelRequested = false
            clearNavigationRead()
            update(state.copy(progress = JourneyProgress(), showErrors = false))
        }
        val dismiss: () -> Unit = { cancelRequested = false }
        val description = if (navigationSnapshot?.guidanceActive == false) "입력한 장소와 시간은 남겨둘게요" else
            "입력한 장소와 시간은 남겨둘게요. 차량 내비의 안내 상태는 바꾸지 않아요"
        if (isVehicle) VehicleConfirmationDialog(
            title = "진행 중인 계획을 중단할까요?",
            description = description,
            confirmLabel = "계획 중단", onConfirm = cancel, onDismiss = dismiss,
        ) else AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("진행 중인 계획을 중단할까요?") },
            text = { Text(description) },
            confirmButton = { TextButton(onClick = cancel) { Text("계획 중단") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("계속 진행") } },
        )
    }
    resumeConfirmation?.takeIf { editingAllowed && !sharedMode }?.let { pending ->
        val actual = pending.approval.snapshot
        val current = if (actual.guidanceActive) "현재 내비: ${actual.destination?.name ?: "목적지 확인 필요"}" else "현재 길안내는 종료돼 있어요"
        val order = (pending.plan.waypoints + pending.plan.destination).joinToString(" → ") { it.name }
        val description = "$current\n\n남은 순서: $order\n완료한 볼일 ${pending.progress.completedVisits}건은 유지해요"
        val dismiss: () -> Unit = { resumeConfirmation = null }
        val confirm: () -> Unit = {
            if (restrictions.state.value.editingAllowed && state.progress == pending.progress && !sending && !reading) {
                resumeConfirmation = null
                sending = true
                navigationMessage = "남은 순서를 차량 내비에 반영하고 있어요"
                navigationError = false
                scope.launch {
                    try {
                        val result = gateway.requestRoute(pending.plan, pending.approval)
                        if (state.progress == pending.progress) when (result) {
                            is RouteRequestResult.Confirmed -> {
                                navigationSnapshot = result.snapshot
                                navigationMessage = "완료한 볼일을 제외한 남은 순서로 안내를 재개했어요"
                            }
                            is RouteRequestResult.Rejected -> {
                                navigationSnapshot = result.snapshot
                                navigationMessage = result.reason
                                navigationError = true
                            }
                            RouteRequestResult.Unavailable -> {
                                navigationMessage = "차량 내비 연결을 확인한 뒤 다시 시도해주세요"
                                navigationError = true
                            }
                        }
                    } finally { sending = false }
                }
            }
        }
        if (isVehicle) VehicleConfirmationDialog(
            title = "남은 순서로 안내를 재개할까요?", description = description,
            confirmLabel = "이 순서로 안내", onConfirm = confirm, onDismiss = dismiss,
        ) else AlertDialog(
            onDismissRequest = dismiss, title = { Text("남은 순서로 안내를 재개할까요?") },
            text = { Text(description) }, confirmButton = { TextButton(onClick = confirm) { Text("이 순서로 안내") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("닫기") } },
        )
    }
}
