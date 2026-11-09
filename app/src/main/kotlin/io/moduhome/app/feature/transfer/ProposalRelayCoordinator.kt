package io.moduhome.app.feature.transfer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.moduhome.app.core.transfer.*
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.data.transfer.HttpProposalRelayGateway
import io.moduhome.app.data.transfer.ProposalLinkCodec
import io.moduhome.app.data.transfer.ProposalQrCode
import io.moduhome.app.data.transfer.RelayInboxReceipt
import io.moduhome.app.data.transfer.RelayJoinDecodeResult
import io.moduhome.app.data.transfer.RelayLinkCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** All visible and connection state stays in the caller; only cancellable jobs live here. */
@Composable
internal fun rememberProposalRelayActions(
    state: ProposalRelayState,
    readState: () -> ProposalRelayState,
    onStateChange: (ProposalRelayState) -> Unit,
    isVehicle: Boolean,
    interaction: DrivingInteraction,
    readInteraction: () -> DrivingInteraction,
    configuredBaseUrl: String,
    allowLocalHttp: Boolean,
    seedChoices: () -> List<RelaySeedChoice>,
    currentExportLink: () -> String?,
    onSeedReceived: (String) -> Boolean,
    onMessageReceived: (RelayMessage) -> RelayInboxReceipt,
): ProposalRelayActions {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentReader by rememberUpdatedState(readState)
    val currentWriter by rememberUpdatedState(onStateChange)
    val interactionReader by rememberUpdatedState(readInteraction)
    val seedReader by rememberUpdatedState(seedChoices)
    val exportReader by rememberUpdatedState(currentExportLink)
    val seedReceiver by rememberUpdatedState(onSeedReceived)
    val messageReceiver by rememberUpdatedState(onMessageReceived)
    val runtime = remember(lifecycleOwner) { RelayRuntime() }

    fun current() = currentReader()
    fun update(next: ProposalRelayState) { if (runtime.mounted) currentWriter(next) }
    fun manualAllowed(): Boolean = runtime.mounted && interactionReader().editingAllowed &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun receivingAllowed(target: RelayReceiverSession): Boolean = runtime.mounted && isVehicle &&
        current().receiver === target && !current().busy && System.currentTimeMillis() in 0 until target.expiresAtMillis &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun deliveryAllowed(target: RelaySenderSession, message: RelayMessage): Boolean = runtime.mounted && !isVehicle &&
        current().sender === target && current().outgoing == message && !current().busy && current().candidate == null &&
        current().delivery == RelayDeliveryStatus.QUEUED && System.currentTimeMillis() in 0 until target.expiresAtMillis &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun manualMatches(token: Long, expectedInteraction: DrivingInteraction): Boolean = manualAllowed() &&
        current().generation == token && interactionReader() == expectedInteraction
    fun gateway(base: String) = HttpProposalRelayGateway(base, allowLocalHttp)
    fun notice(message: String) { update(current().copy(message = message)) }

    fun cancelManual() {
        val prior = current()
        runtime.action?.cancel()
        runtime.action = null
        update(prior.copy(action = null, operationInteraction = null, vehicleDialogOpen = false,
            candidate = null, reviewedInteraction = null, generation = prior.generation + 1,
            message = if (prior.action == RelayManualAction.SEND) "전송 응답을 확인하지 못했어요. 같은 제안으로 다시 시도할 수 있어요" else prior.message))
    }

    fun launchManual(action: RelayManualAction, prepare: (ProposalRelayState) -> ProposalRelayState = { it },
        block: suspend (Long, DrivingInteraction) -> Unit) {
        if (!manualAllowed() || current().busy || runtime.action?.isActive == true) return
        val token = current().generation + 1
        val expectedInteraction = interactionReader()
        update(prepare(current()).copy(action = action, generation = token, operationInteraction = expectedInteraction))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(token, expectedInteraction)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (runtime.mounted && current().generation == token) notice("연결 요청을 마치지 못했어요. 다시 시도해주세요")
            } finally {
                if (runtime.mounted && current().generation == token) update(current().copy(action = null, operationInteraction = null))
                if (runtime.action === currentCoroutineContext()[Job]) runtime.action = null
            }
        }
        runtime.action = job
        job.start()
    }

    fun openVehicle(browserDropoff: Boolean) {
        if (!isVehicle || !manualAllowed() || current().busy) return
        val choices = if (browserDropoff) emptyList() else seedReader().toList()
        update(current().copy(vehicleDialogOpen = true, browserDropoff = browserDropoff, seedChoices = choices,
            selectedSeedKind = choices.firstOrNull()?.kind, reviewedInteraction = interactionReader(), message = null))
    }

    val actions = ProposalRelayActions(
        openVehicle = { openVehicle(browserDropoff = false) },
        openBrowserDropoff = { openVehicle(browserDropoff = true) },
        chooseSeed = { kind ->
            if (isVehicle && manualAllowed() && !current().busy && current().receiver == null &&
                current().vehicleDialogOpen && (kind == null || current().seedChoices.any { it.kind == kind })) {
                update(current().copy(selectedSeedKind = kind))
            }
        },
        createRoom = create@{
            val before = current()
            if (!isVehicle || !manualAllowed() || before.receiver != null || !before.vehicleDialogOpen ||
                before.reviewedInteraction != interactionReader()) return@create
            if (configuredBaseUrl.isBlank()) { notice("현재 차량 연결을 사용할 수 없어요"); return@create }
            val selected = before.selectedSeedKind?.let { kind -> before.seedChoices.firstOrNull { it.kind == kind } }
            if (selected != null && selected !in seedReader()) {
                notice("선택한 볼일이 바뀌었어요. 연결 창을 다시 열어주세요")
                return@create
            }
            val seed = selected?.proposal(System.currentTimeMillis())
            if (selected != null && seed == null) { notice("공유할 볼일의 장소와 시간을 다시 확인해주세요"); return@create }
            launchManual(RelayManualAction.OPEN) { token, expectedInteraction ->
                var opened: RelayReceiverSession? = null
                var adopted = false
                try {
                    val result = gateway(configuredBaseUrl).openRoom(seed?.let(ProposalLinkCodec::encode))
                    if (result is RelayResult.Success) opened = result.value
                    if (!manualMatches(token, expectedInteraction) || current().receiver != null) return@launchManual
                    if (selected != null && selected !in seedReader()) {
                        notice("선택한 볼일이 바뀌었어요. 연결 창을 다시 열어주세요")
                        return@launchManual
                    }
                    when (result) {
                        is RelayResult.Failure -> notice(result.reason)
                        is RelayResult.Success -> {
                            val room = result.value
                            val (qr, browserQr) = withContext(Dispatchers.Default) {
                                val appCode = ProposalQrCode.encode(RelayLinkCodec.encode(room.sender))?.asImageBitmap()
                                val browserCode = if (seed == null) {
                                    ProposalQrCode.encode(RelayLinkCodec.encodeBrowser(room.sender))?.asImageBitmap()
                                } else null
                                appCode to browserCode
                            }
                            if (!manualMatches(token, expectedInteraction) || current().receiver != null) return@launchManual
                            val displayedQr = if (before.browserDropoff) browserQr else qr
                            update(current().copy(receiver = room, qr = qr, browserQr = browserQr,
                                message = if (displayedQr == null) "연결 QR을 만들지 못했어요. 연결을 종료한 뒤 다시 시도해주세요"
                                    else null))
                            adopted = true
                        }
                    }
                } finally {
                    val abandoned = opened
                    if (!adopted && abandoned != null && manualAllowed() && current().receiver?.roomId != abandoned.roomId) {
                        withContext(NonCancellable) {
                            withTimeoutOrNull(1_500L) { gateway(abandoned.baseUrl).close(abandoned) }
                        }
                    }
                }
            }
        },
        closeRoom = {
            val room = current().receiver
            if (isVehicle && room != null && manualAllowed()) {
                launchManual(RelayManualAction.CLOSE) { token, expectedInteraction ->
                    val result = gateway(room.baseUrl).close(room)
                    if (!manualMatches(token, expectedInteraction) || current().receiver !== room) return@launchManual
                    when (result) {
                        is RelayResult.Failure -> notice(result.reason + " 연결 종료를 다시 시도할 수 있어요")
                        is RelayResult.Success -> update(current().copy(receiver = null, qr = null, browserQr = null,
                            message = "연결을 종료했어요. 이미 받은 제안은 수신함에 남아요"))
                    }
                }
            }
        },
        dismissVehicle = { if (!current().busy) update(current().copy(vehicleDialogOpen = false, reviewedInteraction = null)) },
        acceptJoinLink = accept@{ link ->
            if (isVehicle) { notice("차량에서는 휴대폰 연결 QR을 보여줘요. 이 링크는 휴대폰에서 열어주세요"); return@accept true }
            if (!manualAllowed() || current().busy) return@accept false
            when (val decoded = RelayLinkCodec.decode(link, System.currentTimeMillis(), allowLocalHttp)) {
                is RelayJoinDecodeResult.Invalid -> notice(decoded.reason)
                is RelayJoinDecodeResult.Valid -> update(current().copy(candidate = decoded.session,
                    reviewedInteraction = interactionReader(), message = null))
            }
            true
        },
        joinVehicle = {
            val candidate = current().candidate
            val oldSender = current().sender
            if (!isVehicle && candidate != null && manualAllowed() && current().reviewedInteraction == interactionReader()) {
                launchManual(RelayManualAction.JOIN) { token, expectedInteraction ->
                    val result = gateway(candidate.baseUrl).join(candidate)
                    if (!manualMatches(token, expectedInteraction) || current().candidate !== candidate || current().sender !== oldSender) return@launchManual
                    when (result) {
                        is RelayResult.Failure -> notice(result.reason)
                        is RelayResult.Success -> {
                            update(current().copy(sender = candidate, candidate = null, reviewedInteraction = null,
                                outgoing = null, delivery = null, message = "차량에 연결했어요. 보낼 볼일을 확인해주세요"))
                            val seed = result.value.seedLink
                            if (seed != null && !seedReceiver(seed)) notice("연결했지만 장소 제안을 보관하지 못했어요. 다시 연결해 확인해주세요")
                            else if (seed == null) notice("차량에 연결했어요. 보낼 하차지나 볼일을 확인해주세요")
                        }
                    }
                }
            }
        },
        dismissJoin = { if (!current().busy) update(current().copy(candidate = null, reviewedInteraction = null)) },
        disconnectSender = {
            if (!isVehicle && manualAllowed() && !current().busy) update(current().copy(sender = null, candidate = null,
                outgoing = null, delivery = null, generation = current().generation + 1,
                message = "이 휴대폰의 연결을 해제했어요"))
        },
        send = send@{ link ->
            val sender = current().sender ?: return@send
            if (isVehicle || !manualAllowed() || current().candidate != null || link != exportReader() ||
                System.currentTimeMillis() !in 0 until sender.expiresAtMillis) return@send
            if (ProposalLinkCodec.decode(link, System.currentTimeMillis()) !is io.moduhome.app.data.transfer.ProposalDecodeResult.Valid) {
                notice("보낼 제안의 장소와 시간을 다시 확인해주세요")
                return@send
            }
            val message = current().outgoing?.takeIf { it.link == link } ?: RelayMessage(UUID.randomUUID().toString(), link)
            launchManual(RelayManualAction.SEND, prepare = { it.copy(outgoing = message, delivery = null, message = null) }) { token, expectedInteraction ->
                val result = gateway(sender.baseUrl).send(sender, message)
                if (!manualMatches(token, expectedInteraction) || current().sender !== sender || current().outgoing != message) return@launchManual
                when (result) {
                    is RelayResult.Failure -> {
                        if (result.code == RelayFailure.CLOSED || result.code == RelayFailure.UNAUTHORIZED) {
                            update(current().copy(sender = null, outgoing = null, delivery = null,
                                message = "차량 연결을 다시 열어주세요. 만든 제안은 그대로 남아 있어요"))
                        } else notice(result.reason + " 같은 제안으로 다시 시도할 수 있어요")
                    }
                    is RelayResult.Success -> update(current().copy(delivery = result.value, message = relayDeliveryMessage(result.value)))
                }
            }
        },
    )

    DisposableEffect(runtime, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) cancelManual()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runtime.mounted = false
            runtime.action?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
            val latest = currentReader()
            currentWriter(latest.copy(action = null, operationInteraction = null, candidate = null,
                vehicleDialogOpen = false, reviewedInteraction = null, generation = latest.generation + 1))
        }
    }
    LaunchedEffect(interaction) {
        val now = current()
        if (!interactionReader().editingAllowed ||
            (now.reviewedInteraction != null && now.reviewedInteraction != interactionReader()) ||
            (now.operationInteraction != null && now.operationInteraction != interactionReader())) cancelManual()
    }
    LaunchedEffect(state.receiver, state.sender, state.candidate) {
        while (currentCoroutineContext().isActive) {
            val before = current()
            val expiries = listOfNotNull(before.receiver?.expiresAtMillis, before.sender?.expiresAtMillis, before.candidate?.expiresAtMillis)
            if (expiries.isEmpty()) return@LaunchedEffect
            val now = System.currentTimeMillis()
            val expired = before.expire(now)
            if (expired != null) {
                runtime.action?.cancel()
                update(expired)
                return@LaunchedEffect
            }
            delay((expiries.min() - now).coerceIn(1L, 30_000L))
        }
    }
    LaunchedEffect(state.receiver, state.action, lifecycleOwner) {
        val room = state.receiver
        if (!isVehicle || room == null || state.busy) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive && receivingAllowed(room)) {
                when (val received = gateway(room.baseUrl).pending(room)) {
                    is RelayResult.Failure -> if (receivingAllowed(room)) {
                        if (received.code == RelayFailure.CLOSED || received.code == RelayFailure.UNAUTHORIZED) update(current().copy(receiver = null, qr = null, browserQr = null,
                            generation = current().generation + 1, message = "연결이 종료됐어요. 이미 받은 제안은 보관돼요"))
                        else notice(received.reason)
                    }
                    is RelayResult.Success -> for (message in received.value) {
                        if (!receivingAllowed(room)) break
                        val receipt = messageReceiver(message)
                        if (!receivingAllowed(room)) break
                        receipt.reason?.let(::notice)
                        val acknowledgement = receipt.acknowledgement ?: continue
                        val acknowledged = gateway(room.baseUrl).acknowledge(room, message.messageId, acknowledgement)
                        if (acknowledged is RelayResult.Failure && receivingAllowed(room)) notice(acknowledged.reason)
                    }
                }
                delay(3_000L)
            }
        }
    }
    LaunchedEffect(state.sender, state.delivery, state.action, state.candidate, lifecycleOwner) {
        val sender = state.sender
        if (isVehicle || sender == null || !state.canCheckSender(sender, System.currentTimeMillis())) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive && runtime.mounted &&
                current().canCheckSender(sender, System.currentTimeMillis())) {
                val generation = current().generation
                val result = gateway(sender.baseUrl).join(sender)
                if (runtime.mounted && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    current().afterSenderCheck(sender, generation, System.currentTimeMillis(), result)?.let(::update)
                }
                delay(10_000L)
            }
        }
    }
    LaunchedEffect(state.sender, state.outgoing, state.delivery, state.action, state.candidate, lifecycleOwner) {
        val sender = state.sender
        val outgoing = state.outgoing
        if (isVehicle || sender == null || outgoing == null || state.delivery != RelayDeliveryStatus.QUEUED || state.busy || state.candidate != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive && deliveryAllowed(sender, outgoing)) {
                when (val delivered = gateway(sender.baseUrl).delivery(sender, outgoing.messageId)) {
                    is RelayResult.Failure -> if (deliveryAllowed(sender, outgoing)) {
                        if (delivered.code == RelayFailure.CLOSED || delivered.code == RelayFailure.UNAUTHORIZED) {
                            update(current().copy(sender = null, outgoing = null, delivery = null,
                                message = "차량 연결을 다시 열어주세요. 만든 제안은 그대로 남아 있어요"))
                        } else notice(delivered.reason)
                    }
                    is RelayResult.Success -> if (deliveryAllowed(sender, outgoing)) {
                        update(current().copy(delivery = delivered.value, message = relayDeliveryMessage(delivered.value)))
                    }
                }
                delay(3_000L)
            }
        }
    }
    return actions
}

private class RelayRuntime {
    var mounted = true
    var action: Job? = null
}

internal fun relayDeliveryMessage(status: RelayDeliveryStatus): String = when (status) {
    RelayDeliveryStatus.QUEUED -> "서버에 접수됐어요. 차량 앱이 열린 동안 수신함 보관을 기다려요"
    RelayDeliveryStatus.STORED -> "차량 수신함에 보관됐어요. 운전자가 확인한 뒤 계획에 반영해요"
    RelayDeliveryStatus.REJECTED -> "차량에서 보관할 수 없는 제안이에요. 조건을 확인해 새 제안을 만들어주세요"
}
