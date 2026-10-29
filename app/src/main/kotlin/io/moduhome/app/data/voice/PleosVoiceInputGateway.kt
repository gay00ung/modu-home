package io.moduhome.app.data.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.moduhome.app.BuildConfig
import io.moduhome.app.core.voice.KoreanJourneyVoiceParser
import io.moduhome.app.core.voice.VoiceInputGateway
import io.moduhome.app.core.voice.VoiceInputState
import io.moduhome.app.core.voice.VoiceRecognitionResult
import io.moduhome.app.core.voice.awaitVoiceRecognitionResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Each explicit request owns one ON_DEVICE session. Audio and recognized text are never persisted here. */
internal class PleosVoiceInputGateway(context: Context) : VoiceInputGateway {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val state: StateFlow<VoiceInputState> = mutableState.asStateFlow()
    private var active: Session? = null
    private var closed = false
    private var nextSessionId = 0L

    override suspend fun recognize(): VoiceRecognitionResult = withContext(Dispatchers.Main.immediate) {
        val failed = mutableState.value as? VoiceInputState.Failed
        if (failed != null) return@withContext VoiceRecognitionResult.Unavailable(failed.reason)
        if (closed) return@withContext VoiceRecognitionResult.Unavailable("음성 입력 화면을 다시 열어주세요")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return@withContext VoiceRecognitionResult.Unavailable("Pleos 차량에서 음성 입력을 사용할 수 있어요")
        }
        if (active != null) return@withContext VoiceRecognitionResult.Busy
        val session = try {
            Session(++nextSessionId, SpeechToText(context, Mode.ON_DEVICE))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext VoiceRecognitionResult.Unavailable("차량의 온디바이스 음성 서비스를 사용할 수 없어요")
        }
        active = session
        mutableState.value = VoiceInputState.Preparing
        var shutdownFailure: String? = null
        val result = try {
            recognizeSession(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            VoiceRecognitionResult.Unavailable("차량의 온디바이스 음성 서비스를 사용할 수 없어요")
        } finally {
            shutdownFailure = withContext(NonCancellable + Dispatchers.Main.immediate) { shutdown(session) }
        }
        shutdownFailure?.let { VoiceRecognitionResult.Unavailable(it) } ?: result
    }

    override fun stop() = onMain {
        active?.let(::requestStop)
    }

    override fun close() = onMain {
        closed = true
        stop()
    }

    private suspend fun recognizeSession(session: Session): VoiceRecognitionResult {
        when (val initialized = withTimeoutOrNull(INITIALIZATION_TIMEOUT_MILLIS) { awaitInitialization(session) }) {
            Initialization.Ready -> Unit
            Initialization.Stopped -> return VoiceRecognitionResult.Stopped
            is Initialization.Failed -> return VoiceRecognitionResult.Unavailable(initialized.reason)
            null -> return VoiceRecognitionResult.Unavailable("차량 음성 서비스에 연결하지 못했어요. 다시 시도해주세요")
        }
        if (!isCurrent(session) || session.stopping) return VoiceRecognitionResult.Stopped
        return coroutineScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) { awaitRecognition(session) }
            try {
                awaitVoiceRecognitionResult(result, session.ended)
            } finally {
                result.cancel()
            }
        }
    }

    private fun requestStop(session: Session) {
        if (!isCurrent(session)) return
        session.stopping = true
        mutableState.value = VoiceInputState.Stopping
        val initialization = session.initialization
        session.initialization = null
        if (initialization?.isActive == true) initialization.resume(Initialization.Stopped)
        finish(session, VoiceRecognitionResult.Stopped)
    }

    private suspend fun awaitInitialization(session: Session): Initialization = suspendCancellableCoroutine { continuation ->
        session.initialization = continuation
        continuation.invokeOnCancellation { onMain { requestStop(session) } }
        if (!continuation.isActive || !isCurrent(session) || session.stopping) return@suspendCancellableCoroutine
        try {
            debug(session, "initialize_requested")
            session.sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() = onMain {
                    if (isCurrent(session) && !session.stopping) {
                        debug(session, "initialized")
                        val waiting = session.initialization
                        session.initialization = null
                        if (waiting?.isActive == true) waiting.resume(Initialization.Ready)
                    }
                }

                override fun onFailure(errorType: ErrorType) = onMain {
                    if (isCurrent(session) && !session.stopping) {
                        debug(session, "initialize_failed_${errorType.name}")
                        val waiting = session.initialization
                        session.initialization = null
                        if (waiting?.isActive == true) waiting.resume(Initialization.Failed(errorMessage(errorType)))
                    }
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.initialization = null
            if (continuation.isActive) continuation.resume(Initialization.Failed("차량 음성 서비스를 시작하지 못했어요"))
        }
    }

    private suspend fun awaitRecognition(session: Session): VoiceRecognitionResult = suspendCancellableCoroutine { continuation ->
        session.recognition = continuation
        continuation.invokeOnCancellation { onMain { requestStop(session) } }
        if (!continuation.isActive || !isCurrent(session) || session.stopping) return@suspendCancellableCoroutine
        val listener = object : ResultListener {
            override fun onReady() = onMain {
                if (isCurrent(session)) debug(session, "ready")
                listening(session)
            }
            override fun onStartedRecognition() = onMain {
                if (isCurrent(session)) debug(session, "started")
                listening(session)
            }
            override fun onUpdated(stt: String, completed: Boolean) = onMain {
                if (!isCurrent(session) || session.stopping || session.recognition == null) return@onMain
                if (completed) {
                    debug(session, "final_received")
                    val text = stt.trim()
                    val result = when {
                        text.isBlank() -> VoiceRecognitionResult.NoSpeech
                        text.length > KoreanJourneyVoiceParser.MAX_TEXT_LENGTH ->
                            VoiceRecognitionResult.Unavailable("한 번에 한 가지를 짧게 말해주세요")
                        else -> VoiceRecognitionResult.Recognized(text)
                    }
                    finish(session, result)
                } else {
                    if (!session.ended.isCompleted) {
                        mutableState.value = VoiceInputState.Listening(stt.take(KoreanJourneyVoiceParser.MAX_TEXT_LENGTH))
                    }
                }
            }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onEndedRecognition() = onMain {
                if (isCurrent(session)) {
                    debug(session, "end")
                    if (!session.stopping && session.recognition != null) {
                        debug(session, "awaiting_final")
                        mutableState.value = VoiceInputState.Processing
                    }
                    session.ended.complete(Unit)
                }
            }
            override fun onError(errorType: ErrorType) = onMain {
                if (isCurrent(session)) {
                    debug(session, "error_${errorType.name}")
                    if (!session.stopping) finish(session, VoiceRecognitionResult.Unavailable(errorMessage(errorType)))
                }
            }
        }
        session.listener = listener
        try {
            session.sdk.addListener(listener)
            session.sdk.setLocale(Locale.KOREA)
            if (!continuation.isActive || !isCurrent(session) || session.stopping) return@suspendCancellableCoroutine
            session.requested = true
            debug(session, "request")
            session.sdk.request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            finish(session, VoiceRecognitionResult.Unavailable("차량 마이크에서 음성 입력을 시작하지 못했어요"))
        }
    }

    private fun listening(session: Session) = onMain {
        if (isCurrent(session) && !session.stopping && session.recognition != null && !session.ended.isCompleted) {
            mutableState.value = VoiceInputState.Listening()
        }
    }

    private fun finish(session: Session, result: VoiceRecognitionResult) {
        if (!isCurrent(session)) return
        val continuation = session.recognition
        session.recognition = null
        if (continuation?.isActive == true) continuation.resume(result)
    }

    private suspend fun shutdown(session: Session): String? {
        if (session.released) return session.shutdownFailure
        session.stopping = true
        session.initialization = null
        session.recognition = null
        if (active === session) mutableState.value = VoiceInputState.Stopping
        var failure: String? = null
        if (session.requested && !session.ended.isCompleted) {
            debug(session, "stop_requested")
            try {
                session.sdk.stop()
            } catch (_: Exception) {
                debug(session, "stop_failed")
            }
            val ended = withTimeoutOrNull(STOP_CONFIRMATION_TIMEOUT_MILLIS) {
                session.ended.await()
                true
            } == true
            debug(session, if (ended) "end_confirmed" else "end_timeout")
            if (!ended) failure = "차량 음성 입력 종료를 확인하지 못했어요. 음성 입력을 다시 시작할 수 없어요"
        }
        try {
            session.listener?.let { session.sdk.removeListener(it) }
        } catch (_: Exception) {
            debug(session, "listener_remove_failed")
            if (failure == null) failure = "차량 음성 연결을 해제하지 못했어요. 음성 입력을 다시 시작할 수 없어요"
        }
        try {
            session.sdk.release()
            debug(session, "released")
        } catch (_: Exception) {
            debug(session, "release_failed")
            if (failure == null) failure = "차량 음성 연결을 해제하지 못했어요. 음성 입력을 다시 시작할 수 없어요"
        } finally {
            session.released = true
            session.shutdownFailure = failure
            if (active === session) {
                active = null
                mutableState.value = failure?.let { VoiceInputState.Failed(it) } ?: VoiceInputState.Idle
            }
        }
        return failure
    }

    private fun isCurrent(session: Session): Boolean = active === session && !session.released

    private fun debug(session: Session, event: String) {
        if (BuildConfig.DEBUG) Log.i("ModuVoice", "session=${session.id} $event")
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }

    private fun errorMessage(error: ErrorType): String = when (error) {
        ErrorType.MIC_ERROR -> "차량 마이크를 사용할 수 없어요. 마이크 연결과 음소거 설정을 확인해주세요"
        ErrorType.AUDIO_ERROR -> "차량 마이크 입력을 처리하지 못했어요"
        ErrorType.PERMISSION_DENIED -> "차량 음성 서비스 권한이 허용되지 않았어요"
        ErrorType.ON_DEVICE_ENGINE_ERROR -> "이 차량의 온디바이스 음성 모델을 사용할 수 없어요"
        ErrorType.UNAVAILABLE_LANGUAGE -> "차량의 한국어 음성 모델을 사용할 수 없어요"
        ErrorType.NOT_SUPPORTED_API -> "이 환경에서는 차량 음성 입력을 지원하지 않아요"
        ErrorType.INVALID_REQUEST_ERROR -> "음성 입력을 다시 시작해주세요"
        ErrorType.TIMEOUT -> "음성 입력 응답을 받지 못했어요. 다시 시도해주세요"
        ErrorType.UNAUTHENTICATED, ErrorType.NETWORK_ERROR -> "차량이 온디바이스 음성 요청을 처리하지 못했어요"
        ErrorType.SERVER_INTERNAL_ERROR, ErrorType.UNKNOWN -> "차량 음성 서비스에 오류가 있어요. 다시 시도해주세요"
        else -> "음성 입력을 사용할 수 없어요"
    }

    private class Session(val id: Long, val sdk: SpeechToText) {
        var initialization: CancellableContinuation<Initialization>? = null
        var recognition: CancellableContinuation<VoiceRecognitionResult>? = null
        var listener: ResultListener? = null
        var requested = false
        val ended = CompletableDeferred<Unit>()
        var stopping = false
        var released = false
        var shutdownFailure: String? = null
    }

    private sealed interface Initialization {
        data object Ready : Initialization
        data object Stopped : Initialization
        data class Failed(val reason: String) : Initialization
    }

    companion object {
        private const val INITIALIZATION_TIMEOUT_MILLIS = 8_000L
        private const val STOP_CONFIRMATION_TIMEOUT_MILLIS = 3_000L
    }
}
