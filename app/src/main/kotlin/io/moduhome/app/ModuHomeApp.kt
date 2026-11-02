package io.moduhome.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.core.net.toUri
import io.moduhome.app.core.address.AddressSearchResult
import io.moduhome.app.core.model.JourneyMode
import io.moduhome.app.core.model.JourneyShortcutRequest
import io.moduhome.app.data.notification.JourneyNotifications
import io.moduhome.app.feature.vehicle.journeyShortcut
import io.moduhome.app.feature.vehicle.VehicleJourneyShortcutAction
import io.moduhome.app.data.address.AndroidAddressSearchGateway
import io.moduhome.app.core.transfer.CompanionErrandSource
import io.moduhome.app.core.navigation.GuidanceEstimateResult
import io.moduhome.app.core.navigation.guidanceReadbackDelayMillis
import io.moduhome.app.core.navigation.RouteChangeApproval
import io.moduhome.app.feature.home.NavigationResumeConfirmation
import io.moduhome.app.feature.home.HomeVisitCompletionConfirmation
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.RouteRequestResult
import io.moduhome.app.core.navigation.TripEstimateResult
import io.moduhome.app.core.navigation.VehicleLocationResult
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.core.voice.*
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.JourneyProposalInbox
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.JourneyProposal
import io.moduhome.app.core.transfer.DropoffProposal
import io.moduhome.app.core.transfer.CompanionDropoffDraft
import io.moduhome.app.core.transfer.CompanionDropoffField
import io.moduhome.app.core.transfer.CompanionDropoffResult
import io.moduhome.app.feature.transfer.CompanionAddressPurpose
import io.moduhome.app.feature.transfer.CompanionDropoffEditor
import io.moduhome.app.feature.transfer.companionDropoffDraftSaver
import io.moduhome.app.core.transfer.ProposalReviewStatus
import io.moduhome.app.core.transfer.ProposalReceiveResult
import io.moduhome.app.core.transfer.RelayDeliveryStatus
import io.moduhome.app.core.transfer.RelayMessage
import io.moduhome.app.core.transfer.CompanionErrandDraft
import io.moduhome.app.core.transfer.CompanionErrandField
import io.moduhome.app.core.transfer.CompanionErrandResult
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
import io.moduhome.app.feature.transfer.*
import io.moduhome.app.feature.home.HomeProposalApplyResult
import io.moduhome.app.feature.home.HomeProposalCommitter
import io.moduhome.app.feature.home.HomeProposalCommitResult
import io.moduhome.app.feature.home.proposal
import io.moduhome.app.feature.home.loadRoutedJourney
import io.moduhome.app.feature.home.RoutedJourneyLoadResult
import io.moduhome.app.feature.home.HomeScreen
import io.moduhome.app.feature.transfer.CompanionHomeScreen
import io.moduhome.app.feature.transfer.CompanionAddressStateSaver
import io.moduhome.app.feature.transfer.companionDraftSaver
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.DepartureDraftResult
import io.moduhome.app.feature.home.HomeDepartureResult
import io.moduhome.app.feature.home.JourneyActions
import io.moduhome.app.feature.home.JourneyDraft
import io.moduhome.app.feature.home.NavigationPlacePicker
import io.moduhome.app.feature.home.NavigationPlaceRole
import io.moduhome.app.feature.home.PlacePickerUiState
import io.moduhome.app.feature.home.VehicleNavigationUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun ModuHomeApp(
    incomingProposal: String? = null,
    onProposalConsumed: () -> Unit = {},
    incomingJoin: String? = null,
    onJoinConsumed: () -> Unit = {},
    incomingJourney: JourneyShortcutRequest? = null,
    onJourneyConsumed: () -> Unit = {},
) {
    val uiContext = LocalContext.current
    val context = uiContext.applicationContext
    val isVehicle = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) }
    val journeyNotifications = remember(context) {
        JourneyNotifications(context) { JourneyShortcutIntents.pendingIntent(context, it) }
    }
    var journeyShortcutEnabled by remember(journeyNotifications) { mutableStateOf(journeyNotifications.available) }
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
    var voicePermissionPending by remember { mutableStateOf(false) }
    var voiceStartAfterPermission by remember { mutableStateOf(false) }
    var voicePermissionMessage by remember { mutableStateOf<String?>(null) }
    fun dismissVoice(keepPermissionRequest: Boolean = false) {
        voiceRequest += 1
        voice.stop()
        voiceDialogOpen = false
        voiceCommand = null
        voiceMessage = null
        if (!keepPermissionRequest) {
            voicePermissionPending = false
            voiceStartAfterPermission = false
        }
    }
    DisposableEffect(restrictions, voice, lifecycleOwner) {
        restrictions.connect()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                dismissVoice(keepPermissionRequest = voicePermissionPending)
            }
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
        journeyNotifications.update(JourneyMode.TOGETHER, next.session.journeyShortcut())
        return true
    }
    val scope = rememberCoroutineScope()
    var gatewayRevision by remember { mutableIntStateOf(0) }
    val gateway = remember(context, restrictions, lifecycleOwner, gatewayRevision) {
        PleosNavigationGateway(context) { restrictions.state.value.editingAllowed &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
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
    var visitCompletionConfirmation by remember(gateway) { mutableStateOf<HomeVisitCompletionConfirmation?>(null) }
    var homeNavigationJob by remember(gateway) { mutableStateOf<Job?>(null) }
    var homeNavigationRequest by remember(gateway) { mutableLongStateOf(0L) }
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
    val refreshJourneyShortcuts by rememberUpdatedState {
        journeyShortcutEnabled = journeyNotifications.available
        journeyNotifications.refresh(state.journeyShortcut(), sharedState.session.journeyShortcut())
    }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(journeyNotifications.permissionRequested) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshJourneyShortcuts()
    }
    DisposableEffect(journeyNotifications, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshJourneyShortcuts()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshJourneyShortcuts()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun homeNavigationIdle(): Boolean = !sharedMode && restrictions.state.value.editingAllowed &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !sending && !reading && !estimating && !planning
    fun homeNavigationMatches(request: Long, expected: HomeUiState, limits: DrivingInteraction): Boolean =
        request == homeNavigationRequest && state == expected && !sharedMode &&
            limits == restrictions.state.value && limits.editingAllowed &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun invalidateHomeNavigation() {
        homeNavigationRequest += 1
        homeNavigationJob?.cancel()
        homeNavigationJob = null
        sending = false
        reading = false
        resumeConfirmation = null
        visitCompletionConfirmation = null
    }
    fun launchHomeNavigation(read: Boolean, work: suspend (Long) -> Unit) {
        homeNavigationRequest += 1
        val request = homeNavigationRequest
        if (read) reading = true else sending = true
        homeNavigationJob = scope.launch {
            try { work(request) }
            finally {
                if (request == homeNavigationRequest) {
                    reading = false
                    sending = false
                    homeNavigationJob = null
                }
            }
        }
    }
    DisposableEffect(lifecycleOwner, gateway, interaction, sharedMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) invalidateHomeNavigation()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            invalidateHomeNavigation()
        }
    }
    fun canStartVoice(): Boolean = BuildConfig.ENABLE_VEHICLE_VOICE && isVehicle && !sharedMode &&
        restrictions.state.value.editingAllowed && restrictions.state.value.voiceTranscriptionAllowed &&
        state.progress.stage == JourneyStage.INPUT && voice.state.value == VoiceInputState.Idle &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun startVoice() {
        if (!canStartVoice() || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        voiceRequest += 1
        val request = voiceRequest
        voiceDialogOpen = true
        voiceCommand = null
        voiceMessage = null
        voicePermissionMessage = null
        scope.launch {
            val result = voice.recognize()
            if (request == voiceRequest && voiceDialogOpen && restrictions.state.value.editingAllowed &&
                restrictions.state.value.voiceTranscriptionAllowed) {
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
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = voicePermissionPending
        voicePermissionPending = false
        if (requested) {
            if (granted) {
                voiceStartAfterPermission = true
                val request = voiceRequest
                scope.launch {
                    val resumed = withTimeoutOrNull(1_000) { lifecycleOwner.lifecycle.withResumed { true } } == true
                    if (resumed && voiceStartAfterPermission && request == voiceRequest) {
                        voiceStartAfterPermission = false
                        startVoice()
                    } else voiceStartAfterPermission = false
                }
            } else voicePermissionMessage = "마이크 권한이 없어 음성을 들을 수 없어요. 터치로 선택해주세요"
        }
    }
    var cancelRequested by remember { mutableStateOf(false) }
    var newJourneyConfirmation by remember { mutableStateOf<NewJourneyConfirmation?>(null) }
    val proposalPreferences = remember(context) { ProposalInboxPreferences(context) }
    val restoredProposals = remember(proposalPreferences) { proposalPreferences.load(System.currentTimeMillis()) }
    var inbox by remember { mutableStateOf((restoredProposals as? ProposalInboxLoadResult.Loaded)?.inbox ?: JourneyProposalInbox.empty()) }
    var proposalPanelOpen by rememberSaveable { mutableStateOf(false) }
    var proposalInput by remember { mutableStateOf("") }
    var proposalMessage by remember { mutableStateOf((restoredProposals as? ProposalInboxLoadResult.Failed)?.reason) }
    var exportLink by rememberSaveable { mutableStateOf<String?>(null) }
    var exportName by rememberSaveable { mutableStateOf<String?>(null) }
    var proposalQr by remember { mutableStateOf<ImageBitmap?>(null) }
    var generatingQr by remember { mutableStateOf(false) }
    var readingQr by remember { mutableStateOf(false) }
    var sharedProposalPanelOpen by remember { mutableStateOf(false) }
    var selectedSharedProposal by remember { mutableStateOf<JourneyProposal?>(null) }
    var sharedProposalReview by remember { mutableStateOf<SharedProposalReview?>(null) }
    var sharedProposalInteraction by remember { mutableStateOf<DrivingInteraction?>(null) }
    var sharedProposalMessage by remember { mutableStateOf<String?>(null) }
    var sharedProposalNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var relayState by remember { mutableStateOf(ProposalRelayState()) }
    val addressGateway = remember(context) { AndroidAddressSearchGateway(context) }
    var addressPicker by rememberSaveable(stateSaver = CompanionAddressStateSaver) { mutableStateOf<CompanionAddressState?>(null) }
    var addressRequest by remember { mutableLongStateOf(0L) }
    var addressJob by remember { mutableStateOf<Job?>(null) }
    val draftSaver = remember {
        companionDraftSaver(received = { id, revision ->
            inbox.entries.firstOrNull { it.proposal.id == id && it.proposal.revision == revision &&
                it.status == ProposalReviewStatus.PENDING }?.proposal as? ErrandProposal
        })
    }
    var companionDraft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf<CompanionErrandDraft?>(null) }
    var preparedCompanion by rememberSaveable(stateSaver = draftSaver) { mutableStateOf<CompanionErrandDraft?>(null) }
    val dropoffSaver = remember {
        companionDropoffDraftSaver(received = { id, revision ->
            inbox.entries.firstOrNull { it.proposal.id == id && it.proposal.revision == revision &&
                it.status == ProposalReviewStatus.PENDING }?.proposal as? DropoffProposal
        })
    }
    var dropoffDraft by rememberSaveable(stateSaver = dropoffSaver) { mutableStateOf<CompanionDropoffDraft?>(null) }
    var preparedDropoff by rememberSaveable(stateSaver = dropoffSaver) { mutableStateOf<CompanionDropoffDraft?>(null) }
    var dropoffErrors by remember { mutableStateOf<Map<CompanionDropoffField, String>>(emptyMap()) }
    var companionErrors by remember { mutableStateOf<Map<CompanionErrandField, String>>(emptyMap()) }
    var companionMessage by remember { mutableStateOf<String?>(null) }
    fun canOpenCompanion(): Boolean = !isVehicle && restrictions.state.value.editingAllowed &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !relayState.busy && relayState.candidate == null
    fun canEditCompanion(): Boolean = proposalPanelOpen && canOpenCompanion()
    fun clearAddressSearch(message: String? = null) {
        addressRequest += 1
        addressJob?.cancel()
        addressJob = null
        addressPicker = addressPicker?.clearResults(message)
    }
    fun dismissAddressPicker() {
        clearAddressSearch()
        addressPicker = null
    }
    fun searchAddress() {
        val current = addressPicker ?: return
        if (!canEditCompanion() || current.loading) return
        clearAddressSearch()
        val query = current.query.trim()
        if (query.length !in 3..200) {
            addressPicker = current.clearResults("시·구와 도로명·건물번호를 3~200자로 입력해주세요")
            return
        }
        val request = addressRequest
        addressPicker = current.clearResults().copy(loading = true)
        addressJob = scope.launch {
            val result = addressGateway.search(query)
            val latest = addressPicker
            if (request != addressRequest || !canEditCompanion() || latest == null || latest.query.trim() != query) return@launch
            addressPicker = when (result) {
                is AddressSearchResult.Candidates -> latest.copy(loading = false, candidates = result.items, resultQuery = query)
                AddressSearchResult.NoResults -> latest.clearResults("찾은 주소가 없어요. 시·구와 도로명·건물번호를 확인해주세요")
                AddressSearchResult.TimedOut -> latest.clearResults("주소 검색 응답이 늦어요. 잠시 후 다시 검색해주세요")
                AddressSearchResult.Busy -> latest.clearResults("이전 주소 검색을 정리하고 있어요. 잠시 후 다시 시도해주세요")
                is AddressSearchResult.Unavailable -> latest.clearResults(result.reason)
                is AddressSearchResult.Failed -> latest.clearResults(result.reason)
            }
            addressJob = null
        }
    }
    DisposableEffect(lifecycleOwner, addressGateway) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (addressPicker?.loading == true) {
                    clearAddressSearch("검색을 중단했어요. 주소를 다시 검색해주세요")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            addressJob?.cancel()
        }
    }
    fun dismissCompanion() {
        dropoffDraft = null
        dropoffErrors = emptyMap()
        companionDraft = null
        companionErrors = emptyMap()
        companionMessage = null
    }
    fun canReviewSharedProposal(): Boolean = sharedMode && restrictions.state.value.editingAllowed &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        sharedState.session.progress.stage == JourneyStage.INPUT && !sharedState.busy && !sharedState.dialogOpen &&
        !relayState.vehicleDialogOpen
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
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                dismissSharedProposals()
                newJourneyConfirmation = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun update(next: HomeUiState): Boolean {
        if (!preferences.save(next)) {
            navigationMessage = "계획을 저장하지 못했어요. 다시 시도해주세요"
            navigationError = true
            return false
        }
        if (next.draft != state.draft) {
            exportLink = null
            exportName = null
            proposalQr = null
        }
        state = next
        journeyNotifications.update(JourneyMode.SOLO, next.journeyShortcut())
        return true
    }
    fun clearNavigationRead() {
        navigationMessage = null
        navigationError = false
        navigationSnapshot = null
        tripResult = null
        guidanceResult = null
        resumeConfirmation = null
        visitCompletionConfirmation = null
    }
    fun saveInbox(next: JourneyProposalInbox): Boolean {
        if (!proposalPreferences.save(next)) {
            proposalMessage = "받은 제안을 저장하지 못했어요. 다시 시도해주세요"
            sharedProposalMessage = proposalMessage
            if (sharedMode) sharedState = sharedState.copy(message = proposalMessage, error = true)
            return false
        }
        inbox = next
        return true
    }
    fun receiveProposal(link: String, showReview: Boolean = true): Boolean {
        if (showReview) proposalPanelOpen = restrictions.state.value.editingAllowed && !sharedMode && newJourneyConfirmation == null
        return when (val decoded = ProposalLinkCodec.decode(link.trim(), System.currentTimeMillis())) {
            is ProposalDecodeResult.Invalid -> {
                proposalMessage = decoded.reason
                if (sharedMode) sharedState = sharedState.copy(message = decoded.reason, error = true)
                false
            }
            is ProposalDecodeResult.Valid -> when (val received = inbox.receive(decoded.proposal, System.currentTimeMillis())) {
                is ProposalReceiveResult.Pending -> {
                    if (!saveInbox(received.inbox)) return false
                    proposalInput = ""
                    proposalMessage = "제안을 받았어요. 장소와 시간을 확인해주세요"
                    if (sharedMode) sharedState = sharedState.copy(
                        message = if (sharedState.session.progress.stage == JourneyStage.INPUT)
                            "제안을 받았어요. 받은 제안에서 확인해주세요"
                        else "제안을 보관했어요. 다음 계획에서 확인할 수 있어요", error = false,
                    )
                    true
                }
                ProposalReceiveResult.Duplicate -> {
                    proposalMessage = "이미 받은 제안이에요"
                    saveInbox(inbox)
                }
                is ProposalReceiveResult.Rejected -> {
                    proposalMessage = received.reason
                    false
                }
            }
        }
    }
    fun relaySeedChoices(): List<RelaySeedChoice> {
        if (!isVehicle) return emptyList()
        if (sharedMode) {
            val errand = sharedState.session.draft.errand ?: return emptyList()
            val point = errand.place ?: return emptyList()
            return listOf(RelaySeedChoice(errand.kind, point, errand.stayMinutes, errand.walkingMinutesOneWay,
                errand.ready, errand.deadlineAtMillis))
        }
        return VisitKind.entries.mapNotNull { kind ->
            val enabled = if (kind == VisitKind.PICKUP) state.draft.pickup.enabled else state.draft.groceries.enabled
            if (!enabled) return@mapNotNull null
            val proposal = state.draft.proposal(kind, UUID.randomUUID().toString(), System.currentTimeMillis()) ?: return@mapNotNull null
            RelaySeedChoice(proposal.kind, proposal.place, proposal.stayMinutes, proposal.walkingMinutesOneWay,
                proposal.ready, proposal.deadlineAtMillis)
        }
    }
    val relayActions = rememberProposalRelayActions(
        state = relayState,
        readState = { relayState },
        onStateChange = { relayState = it },
        isVehicle = isVehicle,
        interaction = interaction,
        readInteraction = { restrictions.state.value },
        configuredBaseUrl = BuildConfig.PROPOSAL_RELAY_BASE_URL,
        allowLocalHttp = BuildConfig.DEBUG,
        seedChoices = ::relaySeedChoices,
        currentExportLink = { exportLink },
        onSeedReceived = { receiveProposal(it) },
        onMessageReceived = { message: RelayMessage ->
            val receipt = receiveRelayProposal(inbox, message, System.currentTimeMillis(), ::saveInbox)
            if (receipt.newProposal) {
                val text = if (sharedState.session.progress.stage == JourneyStage.INPUT) "제안을 받았어요. 받은 제안에서 확인해주세요"
                    else "제안을 보관했어요. 다음 계획에서 확인할 수 있어요"
                if (sharedMode) sharedState = sharedState.copy(message = text, error = false)
                else navigationMessage = text
            }
            receipt
        },
    )
    LaunchedEffect(relayState.sender) {
        if (!isVehicle && relayState.sender != null &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) proposalPanelOpen = true
    }
    fun openVehicleRelay() {
        if (!isVehicle || !restrictions.state.value.editingAllowed ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            sending || reading || estimating || planning || sharedState.busy || sharedState.dialogOpen ||
            sharedProposalPanelOpen || picker != null || relayState.busy) return
        dismissVoice()
        proposalPanelOpen = false
        resumeConfirmation = null
        relayActions.openVehicle()
    }
    fun receiveAppLink(link: String) {
        if (RelayLinkCodec.accepts(link.trim())) {
            proposalInput = ""
            scope.launch {
                while (true) {
                    combine(lifecycleOwner.lifecycle.currentStateFlow, snapshotFlow { relayState.busy }, restrictions.state) { phase, busy, limits ->
                        phase.isAtLeast(Lifecycle.State.RESUMED) && !busy && (isVehicle || limits.editingAllowed)
                    }.first { it }
                    if (relayActions.acceptJoinLink(link.trim())) {
                        if (!isVehicle) proposalPanelOpen = relayState.candidate == null
                        else sharedState = sharedState.copy(message = relayState.message, error = true)
                        break
                    }
                }
            }
        } else receiveProposal(link)
    }
    LaunchedEffect(incomingJoin, lifecycleOwner) {
        val link = incomingJoin ?: return@LaunchedEffect
        while (true) {
            combine(lifecycleOwner.lifecycle.currentStateFlow, snapshotFlow { relayState.busy }, restrictions.state) { phase, busy, limits ->
                phase.isAtLeast(Lifecycle.State.RESUMED) && !busy && (isVehicle || limits.editingAllowed)
            }.first { it }
            if (relayActions.acceptJoinLink(link)) {
                if (!isVehicle) proposalPanelOpen = relayState.candidate == null
                else sharedState = sharedState.copy(message = relayState.message, error = true)
                onJoinConsumed()
                break
            }
        }
    }
    LaunchedEffect(sharedState.session.acceptedProposals, state.acceptedProposals, inbox) {
        val restored = state.reconcileProposalInbox(sharedState.session.reconcileProposalInbox(inbox))
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
                    if (link == null) proposalMessage = "QR 하나가 선명하게 보이도록 잘라 다시 선택해주세요"
                    else receiveAppLink(link)
                } finally { readingQr = false }
            }
        }
    }
    fun openCompanionAddress(purpose: CompanionAddressPurpose = CompanionAddressPurpose.ERRAND) {
        if (!canEditCompanion()) return
        exportLink = null
        exportName = null
        preparedCompanion = null
        preparedDropoff = null
        proposalMessage = null
        dismissCompanion()
        dismissAddressPicker()
        addressPicker = CompanionAddressState(session = addressRequest, purpose = purpose)
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
        if (!homeNavigationIdle() || connection != NavigationConnection.Ready) return
        val expected = state
        val limits = restrictions.state.value
        launchHomeNavigation(read = true) { request ->
                val snapshot = gateway.currentRoute()
                if (!homeNavigationMatches(request, expected, limits)) return@launchHomeNavigation
                navigationSnapshot = snapshot
                navigationError = snapshot == null
                navigationMessage = if (snapshot == null) "차량 내비의 경로를 읽지 못했어요" else "현재 차량 내비의 안내 경로예요"
        }
    }
    LaunchedEffect(gateway, connection, sharedMode) {
        if (!sharedMode && connection == NavigationConnection.Ready && state.progress.stage == JourneyStage.ACTIVE && navigationSnapshot == null) {
            readNavigation()
        }
    }
    val guidancePlan = state.navigationWindow
    LaunchedEffect(gateway, connection, routeRevision, state.progress.stage, guidancePlan, lifecycleOwner,
        reading, sending, resumeConfirmation, visitCompletionConfirmation, sharedMode) {
        guidanceResult = null
        if (sharedMode || connection != NavigationConnection.Ready || state.progress.stage != JourneyStage.ACTIVE || guidancePlan == null ||
            reading || sending || resumeConfirmation != null || visitCompletionConfirmation != null) {
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val checkingStartedAt = SystemClock.elapsedRealtime()
            try {
                navigationSnapshot = null
                while (true) {
                    guidanceRefreshing = true
                    val result = gateway.currentGuidance(guidancePlan)
                    guidanceResult = result
                    navigationSnapshot = result.observedRoute
                    guidanceRefreshing = false
                    delay(guidanceReadbackDelayMillis(SystemClock.elapsedRealtime() - checkingStartedAt,
                        hasConfirmedGuidance = result is GuidanceEstimateResult.Success))
                }
            } finally {
                guidanceRefreshing = false
                guidanceResult = null
            }
        }
    }
    LaunchedEffect(routeRevision, interaction, state, sharedMode) {
        val pending = resumeConfirmation
        if (pending != null && (sharedMode || !pending.isCurrent(state, interaction, routeRevision))) {
            resumeConfirmation = null
            navigationMessage = "안내 상태가 바뀌었어요. 남은 순서를 다시 확인해주세요"
            navigationError = true
        }
        val completing = visitCompletionConfirmation
        if (completing != null && (sharedMode || !completing.isCurrent(state, interaction, routeRevision))) {
            visitCompletionConfirmation = null
            navigationMessage = "계획이나 안내 상태가 바뀌었어요. 완료할 볼일을 다시 확인해주세요"
            navigationError = true
        }
    }

    fun prepareNavigationResume() {
        if (!homeNavigationIdle() ||
            state.progress.stage != JourneyStage.ACTIVE || connection != NavigationConnection.Ready) return
        val expected = state
        val limits = restrictions.state.value
        val remaining = expected.navigationWindow ?: return
        visitCompletionConfirmation = null
        launchHomeNavigation(read = true) { request ->
                val before = gateway.routeRevision.value
                val snapshot = gateway.currentRoute()
                val approval = RouteChangeApproval.fromObservation(snapshot, before, gateway.routeRevision.value)
                if (homeNavigationMatches(request, expected, limits)) {
                    navigationSnapshot = snapshot
                    if (approval == null) {
                        navigationMessage = "현재 내비 안내를 확인하지 못했어요. 다시 시도해주세요"
                        navigationError = true
                    } else resumeConfirmation = NavigationResumeConfirmation(remaining, approval, expected, limits)
                }
        }
    }
    fun showHomeRouteResult(result: RouteRequestResult, destinationName: String) {
        navigationError = result !is RouteRequestResult.Confirmed
        when (result) {
            is RouteRequestResult.Confirmed -> {
                navigationSnapshot = result.snapshot
                navigationMessage = "이번 안내 목적지: $destinationName · 남은 방문 순서는 유지돼요"
            }
            is RouteRequestResult.Rejected -> {
                navigationSnapshot = result.snapshot
                navigationMessage = "진행 기록은 저장했어요. ${result.reason}"
            }
            RouteRequestResult.Unavailable -> navigationMessage = "진행 기록은 저장했어요. 차량 내비 연결 후 남은 순서로 다시 안내해주세요"
        }
    }
    fun prepareVisitCompletion(kind: VisitKind) {
        if (!homeNavigationIdle() || state.progress.stage != JourneyStage.ACTIVE) return
        if (connection == NavigationConnection.Unsupported) {
            update(state.copy(progress = state.progress.completeVisit(state.plans, kind, System.currentTimeMillis())))
            return
        }
        if (connection != NavigationConnection.Ready) return
        val expected = state
        val limits = restrictions.state.value
        resumeConfirmation = null
        launchHomeNavigation(read = true) { request ->
            val before = gateway.routeRevision.value
            val snapshot = gateway.currentRoute()
            if (!homeNavigationMatches(request, expected, limits)) return@launchHomeNavigation
            navigationSnapshot = snapshot
            visitCompletionConfirmation = HomeVisitCompletionConfirmation.fromObservation(
                expected, kind, limits, snapshot, before, gateway.routeRevision.value,
            )
            if (visitCompletionConfirmation == null) {
                navigationMessage = "현재 내비와 계획이 달라요. 남은 순서로 다시 안내한 뒤 완료를 확인해주세요"
                navigationError = true
            }
        }
    }
    fun confirmVisitCompletion(pending: HomeVisitCompletionConfirmation) {
        val limits = restrictions.state.value
        if (!homeNavigationIdle() || visitCompletionConfirmation != pending ||
            !pending.isCurrent(state, limits, gateway.routeRevision.value)) return
        visitCompletionConfirmation = null
        launchHomeNavigation(read = true) { request ->
            val before = gateway.routeRevision.value
            val snapshot = gateway.currentRoute()
            if (!homeNavigationMatches(request, pending.state, limits)) return@launchHomeNavigation
            val completion = pending.afterRead(state, restrictions.state.value, snapshot, before,
                gateway.routeRevision.value, System.currentTimeMillis())
            if (completion == null) {
                navigationMessage = "확인 중 내비나 입력 제한이 바뀌었어요. 볼일 완료를 다시 확인해주세요"
                navigationError = true
                return@launchHomeNavigation
            }
            // Physical completion survives an interrupted or rejected SDK transmission.
            if (!update(completion.state)) return@launchHomeNavigation
            val remaining = completion.state.navigationWindow ?: return@launchHomeNavigation
            if (!completion.navigationRequired) {
                navigationSnapshot = snapshot
                navigationMessage = if (snapshot?.guidanceActive == true) "완료를 기록했어요. 현재 내비의 남은 안내를 유지해요" else
                    "완료만 기록했어요. 안내가 필요하면 남은 순서로 다시 안내를 눌러주세요"
                navigationError = false
                return@launchHomeNavigation
            }
            reading = false
            sending = true
            guidanceResult = null
            navigationError = false
            navigationMessage = "완료를 저장했어요. 남은 방문 순서는 유지돼요"
            val result = gateway.requestRoute(remaining, completion.approval)
            if (homeNavigationMatches(request, completion.state, limits)) showHomeRouteResult(result, remaining.destination.name)
        }
    }
    val roadComparisonAvailable = routeGateway != null && connection == NavigationConnection.Ready &&
        state.draft.navigationPoint(NavigationPlaceRole.HOME) != null &&
        (!state.draft.pickup.enabled || state.draft.navigationPoint(NavigationPlaceRole.PICKUP) != null) &&
        (!state.draft.groceries.enabled || state.draft.navigationPoint(NavigationPlaceRole.GROCERIES) != null)

    fun requestNewJourney(reason: String) {
        if (!restrictions.state.value.editingAllowed || sharedMode || sending || reading || estimating || planning ||
            state.progress.stage == JourneyStage.ACTIVE || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        proposalPanelOpen = false
        dismissCompanion()
        dismissAddressPicker()
        dismissPicker()
        dismissVoice()
        newJourneyConfirmation = NewJourneyConfirmation(state, reason)
    }

    fun compareRoadRoutes() {
        val routes = routeGateway ?: return
        if (!editingAllowed || planning || sending || reading || estimating || state.progress.stage != JourneyStage.INPUT) return
        val previous = state.draft
        val draft = when (val rebased = DepartureDraftResult.prepare(previous.copy(origin = "현재 차량 위치"),
            System.currentTimeMillis(), ZoneId.systemDefault())) {
            is DepartureDraftResult.Ready -> rebased.draft
            is DepartureDraftResult.CalendarReview -> { requestNewJourney(rebased.reason); return }
            is DepartureDraftResult.Invalid -> {
                update(state.copy(showErrors = true)); navigationMessage = rebased.reason; navigationError = true
                return
            }
        }
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
                            val comparedDraft = when (val latest = DepartureDraftResult.prepare(draft, System.currentTimeMillis(), ZoneId.systemDefault())) {
                                is DepartureDraftResult.Ready -> latest.draft
                                is DepartureDraftResult.CalendarReview -> {
                                    navigationMessage = latest.reason; navigationError = true
                                    return@launch
                                }
                                is DepartureDraftResult.Invalid -> {
                                    navigationMessage = latest.reason; navigationError = true
                                    return@launch
                                }
                            }
                            if (!result.estimates.isFresh(System.currentTimeMillis())) {
                                navigationMessage = "경로를 확인하는 동안 응답이 오래됐어요. 다시 비교해주세요"
                                navigationError = true
                                return@launch
                            }
                            val next = state.copy(draft = comparedDraft, routing = result.estimates)
                            if (update(next.copy(progress = next.progress.compare(next.plans)))) {
                                navigationMessage = "방문 순서별 도로 경로를 확인했어요"
                            }
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
            dismissCompanion()
            dismissAddressPicker()
            cancelRequested = false
            newJourneyConfirmation = null
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
        onUseAdditionalTime = use@{ suggestion ->
            if (!homeNavigationIdle()) return@use
            if (isVehicle && (!roadComparisonAvailable || connection != NavigationConnection.Ready)) return@use
            val next = state.withAdditionalTime(suggestion) ?: return@use
            clearNavigationRead()
            if (!update(next)) return@use
            if (roadComparisonAvailable) compareRoadRoutes()
            else update(state.copy(progress = state.progress.compare(state.plans)))
        },
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
        onStart = start@{
            if (homeNavigationIdle() && state.progress.stage == JourneyStage.REVIEW) {
                val prepared = if (state.routing != null) when (val result = HomeDepartureResult.prepare(
                    state, System.currentTimeMillis(), ZoneId.systemDefault())) {
                    is HomeDepartureResult.Ready -> result.state
                    is HomeDepartureResult.Recompare -> {
                        if (update(result.state)) { navigationMessage = result.reason; navigationError = true }
                        return@start
                    }
                    is HomeDepartureResult.Refresh -> {
                        if (update(result.state)) { navigationMessage = result.reason; navigationError = true }
                        return@start
                    }
                    is HomeDepartureResult.CalendarReview -> { requestNewJourney(result.reason); return@start }
                    is HomeDepartureResult.Rejected -> {
                        navigationMessage = result.reason; navigationError = true
                        return@start
                    }
                } else state
                val selected = prepared.selectedPlan?.takeIf { it.isValid } ?: return@start
                if (connection == NavigationConnection.Unsupported) {
                    update(prepared.copy(progress = prepared.progress.start(prepared.plans, System.currentTimeMillis())))
                } else {
                    val plan = prepared.draft.navigationPlan(selected)
                    if (plan == null) {
                        navigationMessage = "입력 화면에서 집과 들를 곳의 내비 장소를 선택해주세요"
                        navigationError = true
                    } else if (connection == NavigationConnection.Ready) {
                        val started = prepared.copy(progress = prepared.progress.start(prepared.plans, System.currentTimeMillis()))
                        // Persist the itinerary intent before changing the external navigator.
                        if (!update(started)) return@start
                        val limits = restrictions.state.value
                        clearNavigationRead()
                        navigationMessage = "계획을 저장했어요. 이번 안내 목적지를 확인하고 있어요"
                        launchHomeNavigation(read = false) { request ->
                            val result = gateway.requestRoute(plan)
                            if (homeNavigationMatches(request, started, limits)) showHomeRouteResult(result, plan.destination.name)
                        }
                    }
                }
            }
        },
        onCompleteVisit = ::prepareVisitCompletion,
        onFinish = { if (homeNavigationIdle()) update(state.copy(progress = state.progress.finish(state.plans, System.currentTimeMillis()))) },
        onBack = { if (editingAllowed && !sending) update(state.copy(progress = state.progress.back())) },
        onEdit = { if (editingAllowed && !sending) {
            clearNavigationRead()
            update(state.copy(progress = JourneyProgress(), showErrors = false))
        } },
        onCancel = { if (editingAllowed) cancelRequested = true },
        onNewJourney = {
            requestNewJourney("집과 들를 곳, 체류·도보시간은 유지해요")
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
    LaunchedEffect(incomingJourney, sharedMode, state.progress, sharedState.session.progress, interaction, lifecycleOwner) {
        val request = incomingJourney ?: return@LaunchedEffect
        if (!isVehicle) {
            onJourneyConsumed()
            return@LaunchedEffect
        }
        if (!interaction.editingAllowed) return@LaunchedEffect
        lifecycleOwner.lifecycle.withResumed {
            val current = if (sharedMode) sharedState.session.journeyShortcut() else state.journeyShortcut()
            if (current?.request == request) {
                detailsOpen = false
                dismissVoice()
                dismissPicker()
                dismissSharedProposals()
                proposalPanelOpen = false
                relayActions.dismissVehicle()
            }
            onJourneyConsumed()
        }
    }
    fun openJourneyShortcutSettings() {
        if (!isVehicle || !restrictions.state.value.editingAllowed ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (sharedMode && sharedState.busy || !sharedMode && (sending || reading || estimating || planning)) return
        if (journeyNotifications.permissionRequired && !notificationPermissionRequested) {
            journeyNotifications.recordPermissionRequest()
            notificationPermissionRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!journeyNotifications.openSettings()) {
            val message = "차량 알림 설정을 열지 못했어요. 앱 목록에서 모두 집으로를 다시 열어주세요"
            if (sharedMode) sharedState = sharedState.copy(message = message, error = true)
            else {
                navigationMessage = message
                navigationError = true
            }
        }
    }
    val journeyShortcutActions: (@Composable (Boolean) -> Unit)? = if (isVehicle && !journeyShortcutEnabled) {
        { enabled ->
            val label = if (journeyNotifications.permissionRequired && !notificationPermissionRequested)
                "계획 바로가기 켜기" else "차량 알림 설정 열기"
            VehicleJourneyShortcutAction(label, enabled, ::openJourneyShortcutSettings)
        }
    } else null
    val vehicleState = VehicleNavigationUiState(
        connection, sending, reading || estimating || planning, navigationMessage, navigationError, navigationSnapshot,
        tripResult = tripResult, tripNow = tripNow, roadComparisonAvailable = roadComparisonAvailable, planning = planning,
        guidanceResult = guidanceResult, guidanceRefreshing = guidanceRefreshing,
    )
    if (!isVehicle) {
        CompanionHomeScreen(
            connected = relayState.sender != null,
            pendingProposalCount = inbox.entries.count { it.status == ProposalReviewStatus.PENDING },
            busy = relayState.busy || readingQr || relayState.candidate != null || !editingAllowed,
            message = relayState.message,
            onReadQr = {
                if (canOpenCompanion() && !readingQr) {
                    proposalPanelOpen = true
                    proposalMessage = null
                    qrPicker.launch("image/*")
                }
            },
            onCreateErrand = {
                if (canOpenCompanion()) {
                    proposalPanelOpen = true
                    openCompanionAddress()
                }
            },
            onCreateDropoff = {
                if (canOpenCompanion()) {
                    proposalPanelOpen = true
                    openCompanionAddress(CompanionAddressPurpose.DROPOFF)
                }
            },
            onOpenProposals = { if (canOpenCompanion()) proposalPanelOpen = true },
            modifier = Modifier.fillMaxSize(),
        )
    } else if (sharedMode) {
        SharedJourneyFlow(
            state = sharedState,
            onStateChange = ::updateShared,
            readState = { sharedState },
            gateway = gateway,
            routeGateway = routeGateway,
            interaction = interaction,
            pendingProposalCount = inbox.entries.count { it.status == ProposalReviewStatus.PENDING },
            proposalPanelOpen = sharedProposalPanelOpen || relayState.vehicleDialogOpen,
            onOpenProposals = ::openSharedProposals,
            onOpenPhoneConnection = ::openVehicleRelay,
            toolbarActions = journeyShortcutActions,
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
            toolbarActions = journeyShortcutActions,
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
            onOpenPhoneConnection = ::openVehicleRelay,
            voiceAvailable = BuildConfig.ENABLE_VEHICLE_VOICE && interaction.voiceTranscriptionAllowed &&
                !voicePermissionPending && !voiceStartAfterPermission &&
                voiceState !is VoiceInputState.Failed && voiceState != VoiceInputState.Stopping,
            voiceUnavailableMessage = voicePermissionMessage ?: if (BuildConfig.ENABLE_VEHICLE_VOICE && voiceState is VoiceInputState.Failed)
                "음성 연결을 확인하지 못했어요. 터치로 계획을 선택해주세요" else null,
            onVoice = {
                if (canStartVoice() && !voicePermissionPending && !voiceStartAfterPermission) {
                    voicePermissionMessage = null
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
                    else {
                        voicePermissionPending = true
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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
    if (isVehicle && relayState.vehicleDialogOpen && editingAllowed) {
        VehiclePhoneConnectionDialog(relayState, BuildConfig.PROPOSAL_RELAY_BASE_URL, relayActions)
    }
    if (!isVehicle && relayState.candidate != null && editingAllowed) {
        PhoneVehicleJoinDialog(relayState, relayActions)
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
            message = if (selected != null && currentEntry?.proposal != selected)
                "제안이 수정됐어요. 다음을 눌러 최신 내용을 확인해주세요" else unavailable ?: sharedProposalMessage,
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
                                message = "받은 제안을 반영했어요. 계획을 비교해 순서를 골라주세요",
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
                    if (saveInbox(inbox.decline(id, revision))) {
                        selectedSharedProposal = null
                        sharedProposalReview = null
                        sharedProposalMessage = null
                        val next = inbox.entries.firstOrNull { it.status == ProposalReviewStatus.PENDING }
                        if (next != null) selectSharedProposal(next.proposal.id, next.proposal.revision)
                    }
                }
            },
            onChoosePassenger = { id ->
                if (canReviewSharedProposal() && sharedProposalReview == visibleReview && visibleInteraction == restrictions.state.value) {
                    sharedProposalReview = visibleReview?.forPassenger(id)
                }
            },
            onConfirmDoorstep = { confirmed ->
                if (canReviewSharedProposal() && sharedProposalReview == visibleReview && visibleInteraction == restrictions.state.value) {
                    sharedProposalReview = visibleReview?.confirmDoorstep(confirmed)
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

    LaunchedEffect(proposalPanelOpen, relayState.candidate) {
        if (!proposalPanelOpen || relayState.candidate != null) {
            dismissCompanion()
            dismissAddressPicker()
        }
    }
    val currentAddressPicker = addressPicker
    if (!isVehicle && currentAddressPicker != null && proposalPanelOpen && editingAllowed && relayState.candidate == null) {
        val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.90f
        Dialog(onDismissRequest = ::dismissAddressPicker, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CompanionAddressPicker(
                query = currentAddressPicker.query,
                candidates = currentAddressPicker.candidates,
                loading = currentAddressPicker.loading,
                message = currentAddressPicker.message,
                selected = currentAddressPicker.selected,
                kind = currentAddressPicker.kind,
                editingAllowed = !relayState.busy,
                onQuery = { query ->
                    if (canEditCompanion() && addressPicker?.session == currentAddressPicker.session) {
                        clearAddressSearch()
                        addressPicker = addressPicker?.copy(query = query.take(201))
                    }
                },
                onSearch = ::searchAddress,
                onSelect = { candidate ->
                    val current = addressPicker
                    if (canEditCompanion() && current != null && current.session == currentAddressPicker.session && !current.loading &&
                        current.resultQuery == current.query.trim() && candidate in current.candidates) {
                        addressPicker = current.copy(selected = candidate)
                    }
                },
                onViewLocation = { candidate ->
                    val current = addressPicker
                    if (canEditCompanion() && current != null && current.session == currentAddressPicker.session &&
                        !current.loading && current.resultQuery == current.query.trim() && candidate in current.candidates) {
                        val lat = java.math.BigDecimal.valueOf(candidate.point.latitude).toPlainString()
                        val lon = java.math.BigDecimal.valueOf(candidate.point.longitude).toPlainString()
                        val intent = Intent(Intent.ACTION_VIEW, "geo:$lat,$lon?q=$lat,$lon".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(intent) }
                        catch (_: android.content.ActivityNotFoundException) {
                            addressPicker = current.copy(message = "지도를 열 앱이 없어요. 표시된 주소가 맞는지 확인해주세요")
                        } catch (_: SecurityException) {
                            addressPicker = current.copy(message = "지도 앱을 열 수 없어요. 표시된 주소가 맞는지 확인해주세요")
                        }
                    }
                },
                onKind = { kind ->
                    if (canEditCompanion() && addressPicker?.session == currentAddressPicker.session) {
                        addressPicker = addressPicker?.copy(kind = kind)
                    }
                },
                purpose = currentAddressPicker.purpose,
                onConfirm = {
                    val current = addressPicker
                    val candidate = current?.confirmedCandidate
                    if (canEditCompanion() && current != null && current.session == currentAddressPicker.session && candidate != null) {
                        if (current.purpose == CompanionAddressPurpose.DROPOFF) {
                            dropoffDraft = CompanionDropoffDraft.forAddress(candidate.point, System.currentTimeMillis(), ZoneId.systemDefault())
                            dropoffErrors = emptyMap()
                        } else companionDraft = CompanionErrandDraft.forAddress(candidate.point, current.kind,
                            System.currentTimeMillis(), ZoneId.systemDefault())
                        companionErrors = emptyMap()
                        companionMessage = null
                        dismissAddressPicker()
                    }
                },
                onDismiss = ::dismissAddressPicker,
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.92f).heightIn(max = maximumHeight),
            )
        }
    }
    val editedCompanion = companionDraft
    if (!isVehicle && editedCompanion != null && proposalPanelOpen && editingAllowed && relayState.candidate == null) {
        val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.90f
        Dialog(onDismissRequest = ::dismissCompanion, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CompanionErrandEditor(
                draft = editedCompanion,
                errors = companionErrors,
                editingAllowed = !relayState.busy,
                message = companionMessage,
                onEdit = { edit ->
                    if (canEditCompanion()) companionDraft?.applyEdit(editedCompanion, edit)?.let { merged ->
                        companionDraft = merged
                        companionErrors = emptyMap()
                        companionMessage = null
                    }
                },
                onPrepare = {
                    if (canEditCompanion() && companionDraft == editedCompanion) {
                        val received = (editedCompanion.source as? CompanionErrandSource.Received)?.proposal
                        val current = received?.let { source -> inbox.entries.firstOrNull { it.proposal.id == source.id } }
                        if (received != null && (current?.proposal != received || current.status != ProposalReviewStatus.PENDING)) {
                            companionMessage = "받은 제안이 바뀌었어요. 닫고 최신 내용을 다시 선택해주세요"
                        } else when (val result = editedCompanion.createProposal(System.currentTimeMillis(), ZoneId.systemDefault())) {
                            is CompanionErrandResult.Invalid -> companionErrors = result.errors
                            is CompanionErrandResult.Created -> {
                                exportName = result.proposal.place.name
                                exportLink = ProposalLinkCodec.encode(result.proposal)
                                preparedDropoff = null
                                preparedCompanion = editedCompanion
                                proposalMessage = "보낼 조건을 확인해주세요. 아직 차량으로 보내지 않았어요"
                                dismissCompanion()
                            }
                        }
                    }
                },
                onDismiss = ::dismissCompanion,
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.92f).heightIn(max = maximumHeight),
            )
        }
    }
    val editedDropoff = dropoffDraft
    if (!isVehicle && editedDropoff != null && proposalPanelOpen && editingAllowed && relayState.candidate == null) {
        val maximumHeight = LocalWindowInfo.current.containerDpSize.height * 0.90f
        Dialog(onDismissRequest = ::dismissCompanion, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CompanionDropoffEditor(editedDropoff, dropoffErrors, !relayState.busy,
                onEdit = { edit ->
                    if (canEditCompanion()) dropoffDraft?.applyEdit(editedDropoff, edit)?.let { merged ->
                        dropoffDraft = merged
                        dropoffErrors = emptyMap()
                    }
                },
                onPrepare = {
                    if (canEditCompanion() && dropoffDraft == editedDropoff) {
                        val source = editedDropoff.source
                        val current = source?.let { value -> inbox.entries.firstOrNull { it.proposal.id == value.id } }
                        if (source != null && (current?.proposal != source || current.status != ProposalReviewStatus.PENDING)) {
                            dropoffErrors = mapOf(CompanionDropoffField.PLACE to "받은 하차지가 바뀌었어요. 최신 내용을 다시 선택해주세요")
                        } else when (val result = editedDropoff.createProposal(System.currentTimeMillis(), ZoneId.systemDefault())) {
                            is CompanionDropoffResult.Invalid -> dropoffErrors = result.errors
                            is CompanionDropoffResult.Created -> {
                                preparedCompanion = null
                                preparedDropoff = editedDropoff.withIssuedProposal(result.proposal)
                                exportName = result.proposal.place.name
                                exportLink = ProposalLinkCodec.encode(result.proposal)
                                proposalMessage = "보낼 하차지와 시간을 확인해주세요. 아직 차량으로 보내지 않았어요"
                                dismissCompanion()
                            }
                        }
                    }
                },
                onDismiss = ::dismissCompanion,
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.92f).heightIn(max = maximumHeight),
            )
        }
    }
    if (!sharedMode && proposalPanelOpen && editingAllowed && companionDraft == null && dropoffDraft == null && addressPicker == null) {
        val exportedProposal = exportLink?.let { link ->
            (ProposalLinkCodec.decode(link, System.currentTimeMillis()) as? ProposalDecodeResult.Valid)?.proposal
        }
        val sameRelayPayload = relayState.outgoing?.link == exportLink
        val relayDelivery = relayState.delivery.takeIf { sameRelayPayload }
        val proposalState = ProposalUiState(inbox, proposalInput, proposalMessage,
                canApply = editingAllowed && state.progress.stage == JourneyStage.INPUT && !sending && !reading && !estimating && !planning && !relayState.busy,
                exportLink = exportLink, exportName = exportName, qr = proposalQr, qrBusy = generatingQr || readingQr,
                pickupAvailable = state.draft.navigationPoint(NavigationPlaceRole.PICKUP) != null,
                groceriesAvailable = state.draft.navigationPoint(NavigationPlaceRole.GROCERIES) != null,
                connectedVehicle = relayState.sender?.let { relayServerLabel(it.baseUrl) },
                relayMessage = when {
                    !sameRelayPayload && relayState.delivery == RelayDeliveryStatus.QUEUED -> "앞서 보낸 제안의 차량 수신을 기다린 뒤 다음 제안을 보낼 수 있어요"
                    !sameRelayPayload && relayState.delivery == RelayDeliveryStatus.STORED -> "앞서 보낸 제안은 차량에 보관됐어요. 새로 만든 제안은 아직 보내지 않았어요"
                    else -> relayState.message
                },
                relayBusy = relayState.busy,
                delivery = relayDelivery,
                exportedProposal = exportedProposal,
                canSendToVehicle = !isVehicle && relayState.sender != null && !relayState.busy && relayState.candidate == null &&
                    exportedProposal != null && relayState.delivery != RelayDeliveryStatus.QUEUED &&
                    (!sameRelayPayload || relayDelivery == null),
                canEdit = !isVehicle && !relayState.busy && relayState.candidate == null,
                canEditPrepared = !isVehicle && (preparedCompanion != null || preparedDropoff != null) && !relayState.busy && relayState.candidate == null)
        val proposalActions = ProposalActions(
                onInput = { proposalInput = it.take(ProposalLinkCodec.MAX_LINK_LENGTH + 1) },
                onReceive = { receiveAppLink(proposalInput) },
                onPaste = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    proposalInput = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(ProposalLinkCodec.MAX_LINK_LENGTH + 1)
                },
                onReadQr = { proposalMessage = null; qrPicker.launch("image/*") },
                onApprove = { id, revision ->
                    if (restrictions.state.value.editingAllowed && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        state.progress.stage == JourneyStage.INPUT && !sending && !reading && !estimating && !planning && !relayState.busy) {
                        when (val applied = state.approveProposal(inbox, id, revision, System.currentTimeMillis())) {
                            is HomeProposalApplyResult.Rejected -> proposalMessage = applied.reason
                            is HomeProposalApplyResult.AlreadyApplied -> {
                                if (saveInbox(applied.inbox)) proposalMessage = "이미 반영한 제안의 수신함 상태를 정리했어요"
                            }
                            is HomeProposalApplyResult.Applied -> when (HomeProposalCommitter(::update, ::saveInbox).commit(applied)) {
                                HomeProposalCommitResult.JOURNEY_FAILED -> proposalMessage = "계획을 저장하지 못했어요. 제안은 그대로 남아 있어요"
                                HomeProposalCommitResult.INBOX_PENDING -> {
                                    clearNavigationRead()
                                    proposalMessage = "계획은 저장했어요. 수신함 저장은 다시 확인해주세요"
                                }
                                HomeProposalCommitResult.COMPLETE -> {
                                    clearNavigationRead()
                                    proposalMessage = "이번 계획에 반영했어요. 조건을 편집해 제안을 만들 수 있어요"
                                }
                            }
                        }
                    }
                },
                onDecline = { id, revision -> saveInbox(inbox.decline(id, revision)) },
                onExport = { kind ->
                    if (!relayState.busy && restrictions.state.value.editingAllowed &&
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        val proposal = state.draft.proposal(kind, UUID.randomUUID().toString(), System.currentTimeMillis())
                        if (proposal == null) proposalMessage = "연결된 장소와 체류·도보·마감 시간을 확인해주세요"
                        else {
                            preparedCompanion = null
                            preparedDropoff = null
                            exportName = proposal.place.name
                            exportLink = ProposalLinkCodec.encode(proposal)
                            proposalMessage = "제안을 만들었어요. 차량으로 보내거나 QR과 링크로 전달할 수 있어요"
                        }
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
                onSendToVehicle = {
                    if (canEditCompanion()) exportLink?.let { link ->
                        proposalMessage = null
                        relayActions.send(link)
                    }
                },
                onDisconnectVehicle = relayActions.disconnectSender,
                onEditPrepared = {
                    if (canEditCompanion()) {
                        preparedDropoff?.let { previous ->
                            val issued = exportLink?.let { link ->
                                (ProposalLinkCodec.decode(link, System.currentTimeMillis()) as? ProposalDecodeResult.Valid)?.proposal as? DropoffProposal
                            }
                            dropoffDraft = if (issued != null && issued.place == previous.place) previous.withIssuedProposal(issued) else previous
                            dropoffErrors = emptyMap()
                        }
                        preparedCompanion?.let { previous ->
                            companionDraft = previous
                            companionErrors = emptyMap()
                            companionMessage = null
                        }
                    }
                },
                onBackToProposals = {
                    if (canEditCompanion()) {
                        exportLink = null
                        exportName = null
                        preparedCompanion = null
                        preparedDropoff = null
                        proposalMessage = null
                    }
                },
                onCreateFromAddress = { openCompanionAddress() },
                onReviewDropoff = { id, revision ->
                    if (isVehicle && restrictions.state.value.editingAllowed && !sending && !reading && !estimating && !planning &&
                        state.progress.stage in setOf(JourneyStage.INPUT, JourneyStage.COMPLETE)) {
                        if (sharedState.session.progress.stage != JourneyStage.INPUT) {
                            proposalMessage = "진행 중인 함께 귀가를 마친 뒤 하차지를 반영해주세요"
                        } else if (sharedPreferences.selectMode(true)) {
                            dismissPicker()
                            dismissVoice()
                            proposalPanelOpen = false
                            detailsOpen = false
                            sharedMode = true
                            openSharedProposals()
                            selectSharedProposal(id, revision)
                        } else proposalMessage = "화면 선택을 저장하지 못했어요"
                    }
                },
                onEdit = { id, revision ->
                    if (canEditCompanion()) {
                        val source = inbox.entries.firstOrNull {
                            it.proposal.id == id && it.proposal.revision == revision && it.status == ProposalReviewStatus.PENDING
                        }?.proposal
                        val now = System.currentTimeMillis()
                        val unavailable = if (now < 0) "기기의 날짜와 시간을 확인해주세요" else source?.unavailableReason(now)
                        if (source == null) proposalMessage = "받은 제안이 바뀌었어요. 최신 내용을 다시 선택해주세요"
                        else if (unavailable != null) proposalMessage = unavailable
                        else {
                            when (source) {
                                is ErrandProposal -> companionDraft = CompanionErrandDraft.from(source, now, ZoneId.systemDefault())
                                is DropoffProposal -> {
                                    dropoffDraft = CompanionDropoffDraft.from(source, now, ZoneId.systemDefault())
                                    dropoffErrors = emptyMap()
                                }
                            }
                            companionErrors = emptyMap()
                            companionMessage = null
                        }
                    }
                },
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
    newJourneyConfirmation?.takeIf { editingAllowed && !sharedMode }?.let { pending ->
        val dismiss = { newJourneyConfirmation = null }
        val confirm: () -> Unit = {
            if (restrictions.state.value.editingAllowed && state == pending.state && !sending && !reading && !estimating && !planning &&
                state.progress.stage != JourneyStage.ACTIVE && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (update(HomeUiState(freshDraft(state.draft), acceptedProposals = state.acceptedProposals))) {
                    newJourneyConfirmation = null
                    clearNavigationRead()
                    navigationMessage = "이전 마감과 픽업 준비 확인을 지웠어요. 오늘 들를 곳을 확인해주세요"
                } else newJourneyConfirmation = pending.copy(error = "새 계획을 저장하지 못했어요. 다시 눌러주세요")
            } else newJourneyConfirmation = null
        }
        val description = listOfNotNull(pending.reason, "이전 마감과 픽업 준비 확인을 지우고 오늘 계획을 새로 만들어요", pending.error).joinToString("\n\n")
        if (isVehicle) VehicleConfirmationDialog(
            title = "오늘 귀가를 새로 계획할까요?", description = description,
            confirmLabel = "오늘 새로 계획", onConfirm = confirm, onDismiss = dismiss,
        ) else AlertDialog(
            onDismissRequest = dismiss, title = { Text("오늘 귀가를 새로 계획할까요?") }, text = { Text(description) },
            confirmButton = { TextButton(onClick = confirm) { Text("오늘 새로 계획") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("닫기") } },
        )
    }
    resumeConfirmation?.takeIf { editingAllowed && !sharedMode }?.let { pending ->
        val actual = pending.approval.snapshot
        val current = if (actual.guidanceActive) "현재 내비: ${actual.destination?.name ?: "목적지 확인 필요"}" else "현재 길안내는 종료돼 있어요"
        val order = (pending.plan.waypoints + pending.plan.destination).joinToString(" → ") { it.name }
        val description = "$current\n\n이번 안내: $order\n마지막 집: ${pending.state.draft.home}\n완료한 볼일 ${pending.progress.completedVisits}건은 유지해요"
        val dismiss: () -> Unit = { resumeConfirmation = null }
        val confirm: () -> Unit = {
            if (homeNavigationIdle() && pending.isCurrent(state, restrictions.state.value, gateway.routeRevision.value)) {
                resumeConfirmation = null
                navigationMessage = "남은 순서를 차량 내비에 반영하고 있어요"
                navigationError = false
                val limits = restrictions.state.value
                launchHomeNavigation(read = false) { request ->
                    val result = gateway.requestRoute(pending.plan, pending.approval)
                    if (homeNavigationMatches(request, pending.state, limits)) showHomeRouteResult(result, pending.plan.destination.name)
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
    visitCompletionConfirmation?.takeIf { editingAllowed && !sharedMode }?.let { pending ->
        val next = pending.nextWindow
        val description = listOfNotNull(
            pending.placeName,
            if (pending.guidanceEnded) "이번 구간의 길안내가 끝났어요. 실제 볼일을 마쳤는지 직접 확인해주세요" else
                "실제로 볼일을 마친 뒤 눌러주세요",
            if (!pending.navigationRequired) {
                if (pending.guidanceEnded) "완료만 기록하고 안내는 시작하지 않아요. 남은 볼일도 실제 완료 후 확인해주세요" else
                    "현재 내비 안내를 유지해요. 내비가 이미 지난 볼일은 다시 추가하지 않아요"
            } else next?.let { "완료 후 안내 순서: ${(it.waypoints + it.destination).joinToString(" → ") { place -> place.name }}" },
            "마지막 집: ${pending.state.draft.home}",
        ).joinToString("\n\n")
        val dismiss: () -> Unit = { visitCompletionConfirmation = null }
        val confirm: () -> Unit = { confirmVisitCompletion(pending) }
        if (isVehicle) VehicleConfirmationDialog(
            title = "${if (pending.kind == VisitKind.PICKUP) "픽업" else "장보기"} 완료를 기록할까요?",
            description = description, confirmLabel = "완료 확인", onConfirm = confirm, onDismiss = dismiss,
        ) else AlertDialog(
            onDismissRequest = dismiss, title = { Text("볼일 완료를 기록할까요?") }, text = { Text(description) },
            confirmButton = { TextButton(onClick = confirm) { Text("완료 확인") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("닫기") } },
        )
    }
}

private data class NewJourneyConfirmation(val state: HomeUiState, val reason: String, val error: String? = null)
