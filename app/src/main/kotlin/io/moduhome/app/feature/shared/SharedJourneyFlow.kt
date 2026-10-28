package io.moduhome.app.feature.shared

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.NavigationGateway
import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.PlaceCatalogResult
import io.moduhome.app.core.navigation.RouteChangeApproval
import io.moduhome.app.core.navigation.RouteRequestResult
import io.moduhome.app.core.navigation.VehicleLocationResult
import io.moduhome.app.core.navigation.remainingAfter
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.SharedJourneyPlan
import io.moduhome.app.core.shared.SharedJourneyProgress
import io.moduhome.app.core.shared.SharedScheduledStop
import io.moduhome.app.core.shared.currentWindow
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.feature.home.NavigationPlaceRole
import io.moduhome.app.feature.home.PlacePickerUiState
import io.moduhome.app.feature.home.VehicleNavigationUiState
import io.moduhome.app.feature.vehicle.VehicleConfirmationDialog
import io.moduhome.app.feature.vehicle.VehiclePlacePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** All renderable state stays in the caller. The runtime reference only coordinates in-flight callbacks. */
@Composable
internal fun SharedJourneyFlow(
    state: SharedJourneyFlowState,
    onStateChange: (SharedJourneyFlowState) -> Boolean,
    readState: () -> SharedJourneyFlowState,
    gateway: NavigationGateway,
    routeGateway: RouteEstimateGateway?,
    interaction: DrivingInteraction,
    onLeave: () -> Unit,
    onReconnect: () -> Unit,
    pendingProposalCount: Int,
    proposalPanelOpen: Boolean,
    onOpenProposals: () -> Unit,
    onOpenPhoneConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentStateReader by rememberUpdatedState(readState)
    val runtime = remember(gateway, lifecycleOwner) { SharedFlowRuntime { currentStateReader() } }
    val currentCallback by rememberUpdatedState(onStateChange)
    val currentInteraction by rememberUpdatedState(interaction)
    val currentOnLeave by rememberUpdatedState(onLeave)
    val currentOnReconnect by rememberUpdatedState(onReconnect)
    val currentOnOpenProposals by rememberUpdatedState(onOpenProposals)
    val currentOnOpenPhoneConnection by rememberUpdatedState(onOpenPhoneConnection)
    val currentProposalPanelOpen by rememberUpdatedState(proposalPanelOpen)
    val connection by gateway.connection.collectAsStateWithLifecycle()
    val routeRevision by gateway.routeRevision.collectAsStateWithLifecycle()

    fun publish(next: SharedJourneyFlowState): Boolean {
        if (!runtime.mounted) return false
        if (currentCallback(next)) {
            return true
        }
        // A failed durable update must leave the previous session authoritative.
        val fallback = runtime.latest.copy(
            planning = false, sending = false, reading = false, polling = false,
            picker = null, confirmation = null, passedStopConfirmation = null,
            cancelRequested = false, finishConfirmation = null,
            operationRevision = runtime.latest.operationRevision + 1,
            message = "변경사항을 저장하지 못했어요. 차량 내비 전송을 멈췄어요", error = true,
        )
        currentCallback(fallback)
        return false
    }

    fun canInteract(): Boolean = runtime.mounted && currentInteraction.editingAllowed &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun idle(allowRouteDialog: Boolean = false, allowPassedStopDialog: Boolean = false): Boolean {
        val current = runtime.latest
        return canInteract() && !currentProposalPanelOpen && !current.busy && runtime.operation?.isActive != true && current.picker == null &&
            !current.cancelRequested && current.finishConfirmation == null && (allowRouteDialog || current.confirmation == null) &&
            (allowPassedStopDialog || current.passedStopConfirmation == null)
    }

    fun operationMatches(token: Long, session: SharedJourneySession): Boolean = canInteract() &&
        runtime.latest.operationRevision == token && runtime.latest.session == session &&
        gateway.connection.value == NavigationConnection.Ready

    fun showError(message: String) {
        publish(runtime.latest.copy(message = message, error = true))
    }

    fun cancelTransient(message: String? = null) {
        val current = runtime.latest
        publish(current.copy(
            planning = false, sending = false, reading = false, polling = false,
            picker = null, confirmation = null, passedStopConfirmation = null,
            cancelRequested = false, finishConfirmation = null,
            operationRevision = current.operationRevision + 1,
            message = message ?: current.message,
        ))
        runtime.operation?.cancel()
        runtime.operation = null
    }

    fun launchOperation(next: SharedJourneyFlowState, block: suspend (Long) -> Unit) {
        if (runtime.operation?.isActive == true) return
        val token = runtime.latest.operationRevision + 1
        if (!publish(next.copy(operationRevision = token))) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (runtime.mounted && runtime.latest.operationRevision == token) {
                    showError("요청을 끝내지 못했어요. 현재 장소와 차량 내비를 다시 확인해주세요")
                }
            } finally {
                if (runtime.mounted && runtime.latest.operationRevision == token) {
                    publish(runtime.latest.copy(planning = false, sending = false, reading = false))
                }
                if (runtime.operation === currentCoroutineContext()[Job]) runtime.operation = null
            }
        }
        runtime.operation = job
        job.start()
    }

    suspend fun sendWindow(token: Long, session: SharedJourneySession, plan: NavigationPlan, approval: RouteChangeApproval?) {
        if (!operationMatches(token, session)) return
        if (!publish(runtime.latest.copy(reading = false, sending = true, confirmation = null,
                message = "남은 경유 순서를 차량 내비에서 확인하고 있어요", error = false))) return
        val result = gateway.requestRoute(plan, approval)
        if (!operationMatches(token, session)) return
        when (result) {
            is RouteRequestResult.Confirmed -> {
                val next = session.copy(progress = session.progress.observeNavigation(result.snapshot))
                publish(runtime.latest.copy(session = next, snapshot = result.snapshot,
                    observedRouteRevision = gateway.routeRevision.value,
                    message = "경유 순서와 운전자 집을 차량 내비에서 확인했어요", error = false))
            }
            RouteRequestResult.Unavailable -> showError("차량 내비 연결을 확인한 뒤 남은 순서를 다시 반영해주세요")
            is RouteRequestResult.Rejected -> publish(runtime.latest.copy(snapshot = result.snapshot,
                message = result.reason, error = true))
        }
    }

    suspend fun prepareRemainingWindow(token: Long, session: SharedJourneySession) {
        val plan = session.progress.pendingWindow ?: return
        val revisionBefore = gateway.routeRevision.value
        val snapshot = gateway.currentRoute()
        val approval = RouteChangeApproval.fromObservation(snapshot, revisionBefore, gateway.routeRevision.value)
        if (!operationMatches(token, session)) return
        if (snapshot?.confirms(plan) == true && approval != null) {
            publish(runtime.latest.copy(session = session.copy(progress = session.progress.observeNavigation(snapshot)),
                snapshot = snapshot, observedRouteRevision = approval.routeRevision,
                message = "차량 내비가 남은 순서를 안내하고 있어요", error = false))
            return
        }
        if (approval == null) {
            publish(runtime.latest.copy(snapshot = snapshot, message = "현재 내비 안내를 안정적으로 확인하지 못했어요. 다시 시도해주세요", error = true))
            return
        }
        val previous = session.progress.confirmedWindow
        if (previous != null && snapshot.continues(previous)) {
            sendWindow(token, session, plan, approval)
        } else {
            publish(runtime.latest.copy(snapshot = snapshot, reading = false,
                observedRouteRevision = approval.routeRevision,
                confirmation = SharedRouteConfirmation(plan, approval, session),
                message = "현재 내비 안내와 남은 순서를 확인해주세요", error = false))
        }
    }

    fun updateDraft(draft: SharedJourneyDraft) {
        val current = runtime.latest
        if (!idle() || current.session.progress.stage != JourneyStage.INPUT) return
        if (draft == current.session.draft) return
        publish(current.copy(session = current.session.resetToInput(draft), errors = emptyList(), message = null, error = false,
            planPage = 0, snapshot = null))
    }

    fun dismissPicker() {
        val current = runtime.latest
        if (current.picker == null) return
        cancelTransient()
    }

    fun loadPlaces(target: SharedPlaceTarget) {
        val current = runtime.latest
        val refreshing = current.picker?.target == target
        if (!canInteract() || current.session.progress.stage != JourneyStage.INPUT || current.busy ||
            runtime.operation?.isActive == true || (!refreshing && current.dialogOpen) || gateway.connection.value != NavigationConnection.Ready) return
        val draft = current.session.draft
        if (target is SharedPlaceTarget.Passenger && draft.passengers.none { it.id == target.id }) return
        if (target == SharedPlaceTarget.Errand && draft.errand == null) return
        val role = when (target) {
            SharedPlaceTarget.DriverHome, is SharedPlaceTarget.Passenger -> NavigationPlaceRole.HOME
            SharedPlaceTarget.Errand -> if (draft.errand?.kind == VisitKind.PICKUP) NavigationPlaceRole.PICKUP else NavigationPlaceRole.GROCERIES
        }
        val selection = SharedPlaceSelection(target, draft, PlacePickerUiState(role))
        launchOperation(current.copy(reading = true, picker = selection, message = null, error = false)) { token ->
            val result = gateway.loadPlaces()
            if (operationMatches(token, current.session) && runtime.latest.picker?.target == target) {
                publish(runtime.latest.copy(picker = selection.copy(ui = PlacePickerUiState(role, loading = false, result = result))))
            }
        }
    }

    fun choosePlace(selection: SharedPlaceSelection, point: NavigationPoint) {
        val current = runtime.latest
        if (!canInteract() || current.busy || current.session.progress.stage != JourneyStage.INPUT ||
            current.picker != selection || current.session.draft != selection.input) return
        val catalog = selection.ui.result as? PlaceCatalogResult.Loaded ?: return
        if (point !in catalog.bookmarks && point !in catalog.recent) return
        val draft = current.session.draft
        val next = when (val target = selection.target) {
            SharedPlaceTarget.DriverHome -> draft.copy(driverHome = point)
            is SharedPlaceTarget.Passenger -> draft.copy(passengers = draft.passengers.map { passenger ->
                if (passenger.id == target.id) passenger.chooseDropoff(point) else passenger
            })
            SharedPlaceTarget.Errand -> {
                val errand = draft.errand ?: return
                draft.copy(errand = if (point == errand.place) errand else errand.copy(place = point, ready = false))
            }
        }
        publish(current.copy(session = current.session.resetToInput(next), picker = null, errors = emptyList(), message = null,
            error = false, snapshot = null, operationRevision = current.operationRevision + 1))
    }

    fun compareRoutes() {
        val current = runtime.latest
        val roads = routeGateway
        if (!idle() || current.session.progress.stage != JourneyStage.INPUT) return
        if (roads == null || gateway.connection.value != NavigationConnection.Ready) {
            showError("차량 내비와 도로 경로 서버의 연결을 확인해주세요")
            return
        }
        val input = current.session.draft
        launchOperation(current.copy(planning = true, errors = emptyList(), message = "모든 방문 순서의 도로 경로를 비교하고 있어요", error = false)) { token ->
            val location = gateway.currentLocation()
            if (!operationMatches(token, current.session)) return@launchOperation
            val result = when (location) {
                is VehicleLocationResult.Available -> loadSharedRoutedJourney(input, location.location.point, roads)
                is VehicleLocationResult.Failed -> SharedRoutedJourneyLoadResult.Failed(location.reason)
                VehicleLocationResult.Unsupported -> SharedRoutedJourneyLoadResult.Failed("Pleos 차량에서 현재 위치를 확인해주세요")
            }
            if (!operationMatches(token, current.session)) return@launchOperation
            when (result) {
                is SharedRoutedJourneyLoadResult.Success -> {
                    val routing = result.estimates
                    val session = current.session.copy(draft = input, progress = SharedJourneyProgress().compare(routing.plans), routing = routing)
                    publish(runtime.latest.copy(session = session, planPage = 0, errors = emptyList(), message = "모든 방문 순서를 같은 도로 데이터로 비교했어요", error = false))
                }
                is SharedRoutedJourneyLoadResult.Failed -> publish(runtime.latest.copy(errors = result.errors.values.toList(), message = result.reason, error = true))
            }
        }
    }

    fun reviewPlan() {
        val current = runtime.latest
        if (!idle() || current.session.progress.stage != JourneyStage.COMPARE) return
        val routing = current.session.routing ?: return
        if (!routing.isFresh(System.currentTimeMillis())) {
            publish(current.copy(session = current.session.resetToInput(), message = "경로 조회 후 5분이 지났어요. 다시 비교해주세요", error = true))
            return
        }
        publish(current.copy(session = current.session.copy(progress = current.session.progress.review(routing.plans)), message = null, error = false))
    }

    fun startJourney() {
        val current = runtime.latest
        if (!idle() || current.session.progress.stage != JourneyStage.REVIEW || gateway.connection.value != NavigationConnection.Ready) return
        val oldRouting = current.session.routing ?: return
        val now = System.currentTimeMillis()
        if (!oldRouting.isFresh(now)) {
            publish(current.copy(session = current.session.resetToInput(), message = "경로 조회 후 5분이 지났어요. 현재 위치에서 다시 비교해주세요", error = true))
            return
        }
        val routing = oldRouting.rebaseDeparture(now)
        if (routing == null) {
            publish(current.copy(session = current.session.resetToInput(), message = "출발 시각을 기준으로 마감과 하차 조건을 다시 확인해주세요", error = true))
            return
        }
        val plan = routing.plans.firstOrNull { it.id == current.session.progress.selectedPlanId } ?: return
        if (!plan.isValid) {
            publish(current.copy(session = current.session.copy(routing = routing, progress = current.session.progress.copy(stage = JourneyStage.COMPARE)),
                message = "지금 출발하면 선택한 조건을 충족하지 못해요. 다른 순서를 확인해주세요", error = true))
            return
        }
        val home = current.session.draft.driverHome ?: return
        val progress = current.session.progress.start(plan, home, now)
        val session = current.session.copy(routing = routing, progress = progress)
        val target = progress.pendingWindow ?: return
        // Durable intent is accepted before the one explicitly requested initial transmission.
        launchOperation(current.copy(session = session, sending = true, message = null, error = false)) { token ->
            sendWindow(token, session, target, approval = null)
        }
    }

    fun completeNextStop() {
        val current = runtime.latest
        if (!idle() || current.session.progress.stage != JourneyStage.ACTIVE) return
        if (current.observedRouteRevision != gateway.routeRevision.value) {
            showError("차량 내비 안내가 바뀌었어요. 현재 안내 확인 후 완료를 눌러주세요")
            return
        }
        val plan = current.session.selectedPlan() ?: return
        val now = System.currentTimeMillis()
        val lastRecordedAt = current.session.progress.completedAtMillis.lastOrNull()
            ?: current.session.progress.startedAtMillis ?: 0L
        if (now < lastRecordedAt) {
            showError("기기 시각이 진행 기록보다 이전이에요. 시간을 확인한 뒤 다시 눌러주세요")
            return
        }
        val progress = current.session.progress.confirmStop(plan, now)
        if (progress == current.session.progress) return
        val session = current.session.copy(progress = progress)
        // The physical completion and next pending window survive failed network/SDK work.
        launchOperation(current.copy(session = session, reading = true, message = "정차 완료를 기록했어요. 남은 순서를 확인해요", error = false)) { token ->
            prepareRemainingWindow(token, session)
        }
    }

    fun retryNavigation() {
        val current = runtime.latest
        if (!idle() || current.session.progress.stage != JourneyStage.ACTIVE || gateway.connection.value != NavigationConnection.Ready) return
        val plan = current.session.selectedPlan() ?: return
        val home = current.session.draft.driverHome ?: return
        val session = current.session.copy(progress = current.session.progress.requestNavigation(plan, home))
        launchOperation(current.copy(session = session, reading = true, message = "현재 내비와 남은 순서를 비교하고 있어요", error = false)) { token ->
            prepareRemainingWindow(token, session)
        }
    }

    fun askPassedStopConfirmation() {
        val current = runtime.latest
        if (!idle() || gateway.connection.value != NavigationConnection.Ready) return
        val revision = gateway.routeRevision.value
        val observed = current.passedStopsObservation(revision) ?: return
        val plan = current.session.selectedPlan() ?: return
        publish(current.copy(passedStopConfirmation = SharedPassedStopConfirmation(
            session = current.session,
            plan = plan,
            count = observed.count,
            snapshot = observed.snapshot.copy(waypoints = observed.snapshot.waypoints.toList()),
            routeRevision = revision,
            interaction = currentInteraction,
        )))
    }

    fun confirmPassedStop(confirmation: SharedPassedStopConfirmation) {
        val current = runtime.latest
        if (!idle(allowPassedStopDialog = true) || current.passedStopConfirmation != confirmation ||
            current.session != confirmation.session || current.session.selectedPlan() != confirmation.plan ||
            currentInteraction != confirmation.interaction ||
            gateway.routeRevision.value != confirmation.routeRevision || gateway.connection.value != NavigationConnection.Ready) return
        val observed = current.passedStopsObservation(confirmation.routeRevision) ?: return
        if (observed.count != confirmation.count || observed.snapshot != confirmation.snapshot) return
        launchOperation(current.copy(passedStopConfirmation = null, reading = true,
            message = "현재 내비 경유 순서를 다시 확인하고 있어요", error = false)) { token ->
            val revisionBefore = gateway.routeRevision.value
            val snapshot = gateway.currentRoute()
            val revisionAfter = gateway.routeRevision.value
            if (!operationMatches(token, confirmation.session)) return@launchOperation
            if (snapshot == null || currentInteraction != confirmation.interaction || revisionBefore != confirmation.routeRevision ||
                revisionAfter != revisionBefore || snapshot != confirmation.snapshot) {
                publish(runtime.latest.copy(snapshot = snapshot, observedRouteRevision = null,
                    message = "확인 중 차량 내비 상태가 바뀌었어요. 현재 안내를 다시 확인해주세요", error = true))
                return@launchOperation
            }
            val plan = confirmation.session.selectedPlan()?.takeIf { it == confirmation.plan } ?: return@launchOperation
            val prior = confirmation.session.progress
            val now = System.currentTimeMillis()
            val lastRecordedAt = prior.completedAtMillis.lastOrNull() ?: prior.startedAtMillis ?: 0L
            if (now < lastRecordedAt) {
                showError("기기 시각이 진행 기록보다 이전이에요. 시간을 확인한 뒤 다시 눌러주세요")
                return@launchOperation
            }
            val progress = prior.confirmPassedStops(plan, snapshot, confirmation.count, now)
            if (progress == prior) {
                showError("완료할 정차와 현재 내비 안내를 다시 확인해주세요")
                return@launchOperation
            }
            val session = confirmation.session.copy(progress = progress)
            // Keep the explicit physical completion even if reflecting the next window fails.
            if (!publish(runtime.latest.copy(session = session, snapshot = snapshot,
                    observedRouteRevision = revisionAfter,
                    message = if (confirmation.count == 2) "확인한 두 정차의 완료를 기록했어요. 남은 순서를 확인해요"
                        else "확인한 정차 완료를 기록했어요. 남은 순서를 확인해요", error = false))) return@launchOperation
            prepareRemainingWindow(token, session)
        }
    }

    fun reconnect() {
        val current = runtime.latest
        if (!idle() || gateway.connection.value !is NavigationConnection.Failed) return
        if (publish(current.copy(snapshot = null, observedRouteRevision = null,
                operationRevision = current.operationRevision + 1,
                message = "진행 기록을 보관하고 차량 내비에 다시 연결해요", error = false))) currentOnReconnect()
    }

    fun confirmRouteChange(confirmation: SharedRouteConfirmation) {
        val current = runtime.latest
        if (!idle(allowRouteDialog = true) || current.confirmation != confirmation || current.session != confirmation.session ||
            gateway.routeRevision.value != confirmation.approval.routeRevision || gateway.connection.value != NavigationConnection.Ready) return
        if (current.session.progress.pendingWindow != confirmation.plan) return
        launchOperation(current.copy(confirmation = null, sending = true, error = false, message = null)) { token ->
            sendWindow(token, confirmation.session, confirmation.plan, confirmation.approval)
        }
    }

    fun back() {
        val current = runtime.latest
        when {
            current.picker != null -> dismissPicker()
            current.confirmation != null -> publish(current.copy(confirmation = null))
            current.passedStopConfirmation != null -> publish(current.copy(passedStopConfirmation = null))
            current.finishConfirmation != null -> publish(current.copy(finishConfirmation = null))
            current.cancelRequested -> publish(current.copy(cancelRequested = false))
            current.busy -> showError("차량 내비 응답을 확인하고 있어요. 잠시 뒤 다시 눌러주세요")
            !canInteract() -> Unit
            else -> when (current.session.progress.stage) {
                JourneyStage.INPUT -> currentOnLeave()
                JourneyStage.COMPARE -> publish(current.copy(session = current.session.copy(progress = SharedJourneyProgress()), planPage = 0, message = null, error = false))
                JourneyStage.REVIEW -> publish(current.copy(session = current.session.copy(progress = current.session.progress.copy(stage = JourneyStage.COMPARE)), message = null, error = false))
                JourneyStage.ACTIVE -> publish(current.copy(cancelRequested = true))
                JourneyStage.COMPLETE -> publish(current.copy(session = current.session.resetToInput(), planPage = 0, message = null, error = false))
            }
        }
    }

    val actions = SharedVehicleActions(
        onDraftChange = ::updateDraft,
        onPassengerCountChange = { count ->
            val draft = runtime.latest.session.draft
            if (count in 1..2 && draft.passengers.size != count) {
                val first = draft.passengers.firstOrNull() ?: SharedPassengerDraft("passenger-1", "동승자 1")
                val people = if (count == 1) listOf(first) else listOf(first,
                    draft.passengers.getOrNull(1) ?: SharedPassengerDraft(if (first.id != "passenger-2") "passenger-2" else "passenger-1", "동승자 2"))
                updateDraft(draft.copy(passengers = people))
            }
        },
        onChooseDriverHome = { loadPlaces(SharedPlaceTarget.DriverHome) },
        onChoosePassengerDropoff = { loadPlaces(SharedPlaceTarget.Passenger(it)) },
        onErrandChoice = { kind ->
            val draft = runtime.latest.session.draft
            val errand = when {
                kind == null -> null
                draft.errand?.kind == kind -> draft.errand
                else -> SharedErrandDraft(kind, stayMinutes = if (kind == VisitKind.PICKUP) 5 else 15)
            }
            updateDraft(draft.copy(errand = errand))
        },
        onChooseErrandPlace = { loadPlaces(SharedPlaceTarget.Errand) },
        onConfirmDropoff = { id, confirmed ->
            val draft = runtime.latest.session.draft
            val now = System.currentTimeMillis()
            updateDraft(draft.copy(passengers = draft.passengers.map { passenger ->
                if (passenger.id != id || passenger.dropoff == null) passenger else passenger.copy(
                    doorstepDropoffConfirmed = confirmed, doorstepConfirmedAtMillis = if (confirmed) now else null,
                    verification = if (confirmed) DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "직접 확인", now)
                        else DropoffVerification(),
                )
            }))
        },
        onCompare = ::compareRoutes,
        onSelectPlan = { id ->
            val current = runtime.latest
            if (idle()) publish(current.copy(session = current.session.copy(progress = current.session.progress.select(id, current.session.plans()))))
        },
        onPlanPageChange = { page ->
            val current = runtime.latest
            if (idle() && current.session.progress.stage == JourneyStage.COMPARE && page in 0 until (current.session.plans().size + 2) / 3) {
                publish(current.copy(planPage = page))
            }
        },
        onReview = ::reviewPlan,
        onStart = ::startJourney,
        onCompleteNextStop = ::completeNextStop,
        onConfirmPassedStop = ::askPassedStopConfirmation,
        onRetryNavigation = ::retryNavigation,
        onFinish = {
            val current = runtime.latest
            val plan = current.session.selectedPlan()
            if (idle() && current.session.progress.stage == JourneyStage.ACTIVE && plan != null &&
                current.session.progress.completedStopCount == plan.stops.size) {
                publish(current.copy(finishConfirmation = current.session.progress))
            }
        },
        onBack = ::back,
        onEdit = {
            val current = runtime.latest
            if (idle() && current.session.progress.stage in listOf(JourneyStage.COMPARE, JourneyStage.REVIEW)) {
                publish(current.copy(session = current.session.copy(progress = SharedJourneyProgress()), message = null, error = false, planPage = 0))
            }
        },
        onNewJourney = {
            val current = runtime.latest
            if (idle() && current.session.progress.stage == JourneyStage.COMPLETE) {
                val draft = current.session.draft
                publish(SharedJourneyFlowState(session = current.session.resetToInput(draft.copy(errand = draft.errand?.copy(ready = false))),
                    operationRevision = current.operationRevision + 1))
            }
        },
        onCancel = {
            val current = runtime.latest
            if (idle() && current.session.progress.stage == JourneyStage.ACTIVE) publish(current.copy(cancelRequested = true))
        },
        onLeave = { if (idle() && runtime.latest.session.progress.stage == JourneyStage.INPUT) currentOnLeave() },
        onReconnect = ::reconnect,
        onOpenProposals = {
            if (idle() && runtime.latest.session.progress.stage == JourneyStage.INPUT) currentOnOpenProposals()
        },
        onOpenPhoneConnection = { if (idle()) currentOnOpenPhoneConnection() },
    )

    DisposableEffect(runtime, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) cancelTransient()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runtime.mounted = false
            runtime.operation?.cancel()
            runtime.operation = null
            lifecycleOwner.lifecycle.removeObserver(observer)
            val current = runtime.latest
            val next = current.copy(planning = false, sending = false, reading = false, polling = false,
                picker = null, confirmation = null, passedStopConfirmation = null,
                cancelRequested = false, finishConfirmation = null,
                operationRevision = current.operationRevision + 1)
            currentCallback(next)
        }
    }

    LaunchedEffect(runtime, interaction.editingAllowed, connection) {
        if (!currentInteraction.editingAllowed || gateway.connection.value != NavigationConnection.Ready) {
            cancelTransient()
        }
    }

    LaunchedEffect(routeRevision, interaction, state.session, state.picker,
        state.confirmation, state.passedStopConfirmation, state.finishConfirmation) {
        val current = runtime.latest
        val confirmation = current.confirmation
        if (confirmation != null && (!currentInteraction.editingAllowed || confirmation.session != current.session ||
                confirmation.approval.routeRevision != gateway.routeRevision.value)) {
            publish(current.copy(confirmation = null, message = "안내 상태가 바뀌었어요. 남은 순서를 다시 확인해주세요", error = true))
        }
        val picker = runtime.latest.picker
        if (picker != null && (runtime.latest.session.progress.stage != JourneyStage.INPUT || picker.input != runtime.latest.session.draft)) dismissPicker()
        val passed = runtime.latest.passedStopConfirmation
        if (passed != null && (passed.session != runtime.latest.session || passed.plan != runtime.latest.session.selectedPlan() ||
                passed.interaction != currentInteraction ||
                passed.routeRevision != gateway.routeRevision.value || !currentInteraction.editingAllowed)) {
            publish(runtime.latest.copy(passedStopConfirmation = null,
                message = "안내나 입력 제한 상태가 바뀌었어요. 정차 완료를 다시 확인해주세요", error = true))
        }
        val finish = runtime.latest.finishConfirmation
        if (finish != null && finish != runtime.latest.session.progress) publish(runtime.latest.copy(finishConfirmation = null))
    }

    LaunchedEffect(runtime, connection, routeRevision, interaction.editingAllowed, state.session.progress.stage,
        state.session.progress.completedStopCount, state.planning, state.sending, state.reading,
        state.confirmation, state.passedStopConfirmation, state.cancelRequested, state.finishConfirmation, lifecycleOwner) {
        if (connection != NavigationConnection.Ready || !interaction.editingAllowed ||
            state.session.progress.stage != JourneyStage.ACTIVE || state.planning || state.sending || state.reading ||
            state.confirmation != null || state.passedStopConfirmation != null || state.cancelRequested ||
            state.finishConfirmation != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                val before = runtime.latest
                val canRead = !before.busy && !before.dialogOpen && runtime.operation?.isActive != true && runtime.mounted
                if (canRead) {
                    val session = before.session
                    val token = before.operationRevision
                    if (!publish(before.copy(polling = true))) return@repeatOnLifecycle
                    try {
                        val revisionBefore = gateway.routeRevision.value
                        val snapshot = gateway.currentRoute()
                        val revisionAfter = gateway.routeRevision.value
                        val stable = revisionBefore == revisionAfter
                        if (runtime.mounted && runtime.latest.operationRevision == token && runtime.latest.session == session &&
                            currentInteraction.editingAllowed && stable) {
                            val observed = session.observeRoute(snapshot)
                            val pending = observed.progress.navigationPending
                            val message = when {
                                snapshot == null -> "차량 내비 안내를 확인하지 못했어요. 남은 순서를 다시 확인해주세요"
                                !snapshot.guidanceActive -> if (session.progress.completedStopCount == session.selectedPlan()?.stops?.size)
                                    "차량 내비 안내가 끝났어요. 집에 도착했다면 귀가를 마쳐주세요" else "차량 내비 안내가 종료됐어요. 남은 순서를 확인해주세요"
                                pending -> "차량 내비 안내와 계획이 달라요. 남은 순서를 확인해주세요"
                                else -> "차량 내비가 계획의 남은 순서를 안내하고 있어요"
                            }
                            publish(runtime.latest.copy(session = observed, snapshot = snapshot,
                                observedRouteRevision = revisionAfter, message = message, error = pending))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } finally {
                        if (runtime.mounted && runtime.latest.operationRevision == token) publish(runtime.latest.copy(polling = false))
                    }
                }
                // A replaced polling effect may still be finishing its cancellation cleanup.
                delay(if (canRead) 30_000 else 250)
            }
        }
    }

    BackHandler { back() }
    val plans = state.session.plans()
    val ui = SharedVehicleUiState(
        draft = state.session.draft, stage = state.session.progress.stage, plans = plans,
        selectedPlanId = state.session.progress.selectedPlanId, completedStops = state.session.progress.completedStopCount,
        planPage = state.planPage, showErrors = state.errors.isNotEmpty(), errors = state.errors,
        navigationPending = state.session.progress.navigationPending ||
            (state.session.progress.stage == JourneyStage.ACTIVE && state.observedRouteRevision != routeRevision),
        passedStopCount = if (connection == NavigationConnection.Ready) state.passedStopsObservation(routeRevision)?.count ?: 0 else 0,
        estimateLabel = state.session.routing?.source, directHomeMinutes = state.session.routing?.directHomeMinutes,
        pendingProposalCount = pendingProposalCount,
    )
    SharedVehicleScreen(ui, actions, VehicleNavigationUiState(
        connection = connection, sending = state.sending, reading = state.reading || state.polling,
        message = state.message, error = state.error, snapshot = state.snapshot,
        roadComparisonAvailable = routeGateway != null, planning = state.planning,
    ), interaction, modifier)

    state.picker?.takeIf { interaction.editingAllowed }?.let { selection ->
        val title = when (val target = selection.target) {
            SharedPlaceTarget.DriverHome -> "운전자 집 선택"
            is SharedPlaceTarget.Passenger -> "${selection.input.passengers.firstOrNull { it.id == target.id }?.name ?: "동승자"} 하차 장소 선택"
            SharedPlaceTarget.Errand -> if (selection.input.errand?.kind == VisitKind.PICKUP) "픽업 장소 선택" else "장보기 장소 선택"
        }
        VehiclePlacePicker(selection.ui, onChoose = { choosePlace(selection, it) },
            onRefresh = { loadPlaces(selection.target) }, onDismiss = ::dismissPicker, title = title)
    }
    state.confirmation?.takeIf { interaction.editingAllowed }?.let { confirmation ->
        VehicleConfirmationDialog(
            title = "현재 내비 안내를 바꿀까요?",
            description = routeChangeDescription(confirmation), confirmLabel = "남은 순서로 안내",
            onConfirm = { confirmRouteChange(confirmation) },
            onDismiss = { publish(runtime.latest.copy(confirmation = null)) },
        )
    }
    state.passedStopConfirmation?.takeIf { interaction.editingAllowed && it.interaction == interaction }?.let { confirmation ->
        val stops = confirmation.plan.stops.drop(confirmation.session.progress.completedStopCount).take(confirmation.count)
        if (confirmation.count in 1..2 && stops.size == confirmation.count) {
            val isDropoff = stops.singleOrNull() is SharedScheduledStop.Dropoff
            VehicleConfirmationDialog(
                title = when {
                    confirmation.count == 2 -> "지난 두 정차를 마쳤나요?"
                    isDropoff -> "이미 하차했나요?"
                    else -> "이미 볼일을 마쳤나요?"
                },
                description = passedStopsDescription(confirmation, stops),
                confirmLabel = when {
                    confirmation.count == 2 -> "두 정차 완료 확인"
                    isDropoff -> "하차 완료 확인"
                    else -> "볼일 완료 확인"
                },
                onConfirm = { confirmPassedStop(confirmation) },
                onDismiss = { publish(runtime.latest.copy(passedStopConfirmation = null)) },
            )
        }
    }
    if (state.cancelRequested && interaction.editingAllowed) {
        VehicleConfirmationDialog(
            title = "함께 귀가 계획을 중단할까요?",
            description = "선택한 장소는 보관해요. 차량 내비의 안내 상태는 바꾸지 않아요",
            confirmLabel = "계획 중단",
            onConfirm = {
                val current = runtime.latest
                if (canInteract() && current.cancelRequested && !current.busy && current.session.progress.stage == JourneyStage.ACTIVE) {
                    publish(current.copy(session = current.session.resetToInput(), cancelRequested = false,
                        confirmation = null, snapshot = null, message = "계획 기록을 중단했어요. 차량 내비의 안내 상태는 바꾸지 않았어요", error = false))
                }
            },
            onDismiss = { publish(runtime.latest.copy(cancelRequested = false)) },
        )
    }
    state.finishConfirmation?.takeIf { interaction.editingAllowed }?.let { progress ->
        VehicleConfirmationDialog(
            title = "집에 도착했나요?",
            description = "완료한 정차 기록을 남기고 이번 귀가를 마쳐요",
            confirmLabel = "집 도착 확인",
            onConfirm = {
                val current = runtime.latest
                val plan = current.session.selectedPlan()
                if (canInteract() && !current.busy && current.finishConfirmation == progress && current.session.progress == progress && plan != null) {
                    val now = System.currentTimeMillis()
                    val lastRecordedAt = progress.completedAtMillis.lastOrNull() ?: progress.startedAtMillis ?: 0L
                    if (now < lastRecordedAt) {
                        publish(current.copy(finishConfirmation = null,
                            message = "기기 시각이 진행 기록보다 이전이에요. 시간을 확인한 뒤 다시 눌러주세요", error = true))
                        return@VehicleConfirmationDialog
                    }
                    val next = progress.finish(plan, now)
                    if (next.stage == JourneyStage.COMPLETE) publish(current.copy(session = current.session.copy(progress = next),
                        finishConfirmation = null, message = "집 도착과 완료한 정차를 기록했어요", error = false))
                    else showError("완료한 정차 기록을 다시 확인해주세요")
                }
            },
            onDismiss = { publish(runtime.latest.copy(finishConfirmation = null)) },
        )
    }
}

private class SharedFlowRuntime(private val readState: () -> SharedJourneyFlowState) {
    val latest: SharedJourneyFlowState get() = readState()
    var operation: Job? = null
    var mounted = true
}

private fun SharedJourneySession.plans(): List<SharedJourneyPlan> = routing?.takeIf { it.matches(draft) }?.plans.orEmpty()

private fun SharedJourneySession.selectedPlan(): SharedJourneyPlan? = plans().firstOrNull { it.id == progress.selectedPlanId }

private data class SharedPassedStopsObservation(val count: Int, val snapshot: NavigationSnapshot)

private fun SharedJourneyFlowState.passedStopsObservation(currentRevision: Long): SharedPassedStopsObservation? {
    if (session.progress.stage != JourneyStage.ACTIVE || observedRouteRevision != currentRevision) return null
    val plan = session.selectedPlan()?.takeIf { it.isValid } ?: return null
    if (plan.stops.getOrNull(session.progress.completedStopCount) == null) return null
    val home = session.draft.driverHome ?: return null
    val pending = session.progress.pendingWindow?.takeIf { it.waypoints.isNotEmpty() } ?: return null
    if (pending != plan.currentWindow(home, session.progress.completedStopCount)) return null
    val observed = snapshot ?: return null
    val count = (1..minOf(2, pending.waypoints.size)).singleOrNull { consumed ->
        observed.confirms(pending.remainingAfter(consumed))
    } ?: return null
    return SharedPassedStopsObservation(count, observed)
}

private fun NavigationSnapshot?.continues(plan: NavigationPlan): Boolean = this?.confirms(plan) == true ||
    (plan.waypoints.isNotEmpty() && this?.confirms(plan.remainingAfter(1)) == true)

/** A suffix keeps the same guidance alive; it never counts as a physical stop completion. */
private fun SharedJourneySession.observeRoute(snapshot: NavigationSnapshot?): SharedJourneySession {
    if (progress.stage != JourneyStage.ACTIVE) return this
    val plan = selectedPlan() ?: return this
    val home = draft.driverHome ?: return this
    val pending = progress.pendingWindow
    if (pending != null) return copy(progress = progress.observeNavigation(snapshot))
    val expected = progress.confirmedWindow ?: plan.currentWindow(home, progress.completedStopCount)
    return if (snapshot.continues(expected)) this else copy(progress = progress.requestNavigation(plan, home))
}

private fun routeChangeDescription(confirmation: SharedRouteConfirmation): String = buildString {
    val snapshot = confirmation.approval.snapshot
    if (snapshot.guidanceActive) {
        append("현재 목적지 · ${snapshot.destination?.name ?: "목적지 확인 필요"}\n")
        if (snapshot.waypoints.isNotEmpty()) append("현재 경유 · ${snapshot.waypoints.joinToString(" → ") { it.name }}\n")
    } else append("현재 차량 내비는 길안내 중이 아니에요\n")
    append("\n남은 정차 · ")
    append(confirmation.plan.waypoints.joinToString(" → ") { it.name }.ifBlank { "중간 정차 없음" })
    append("\n마지막 목적지 · ${confirmation.plan.destination.name}\n\n")
    append("이 순서로 안내를 바꿀까요? 이미 완료한 정차 기록은 그대로 남겨요")
}

private fun passedStopsDescription(confirmation: SharedPassedStopConfirmation, stops: List<SharedScheduledStop>): String = buildString {
    stops.forEachIndexed { index, stop ->
        append("${if (stops.size == 1) "완료할 정차" else "${index + 1}. 완료할 정차"} · ${stop.place.point.name}\n")
        when (stop) {
            is SharedScheduledStop.Dropoff -> append("집 앞 하차 · ${stop.passenger.name}님이 이곳에서 실제로 내렸나요?\n\n")
            is SharedScheduledStop.Errand -> append("${if (stop.errand.visit.kind == VisitKind.PICKUP) "픽업" else "장보기"} · 이곳에서 실제로 볼일을 마쳤나요?\n\n")
        }
    }
    val observed = confirmation.snapshot
    append("현재 내비 경유 · ${observed.waypoints.joinToString(" → ") { it.name }.ifBlank { "중간 정차 없음" }}\n")
    append("마지막 목적지 · ${observed.destination?.name ?: confirmation.session.draft.driverHome?.name.orEmpty()}\n\n")
    append(if (stops.size == 2) "두 정차를 모두 마쳤을 때 확인해주세요. " else "이 정차를 실제로 마쳤을 때 확인해주세요. ")
    append("확인한 완료 기록을 보관하고 나머지 순서의 안내를 확인해요")
}
