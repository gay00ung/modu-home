package io.moduhome.app.data.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.Manifest
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
import io.moduhome.app.core.voice.VoicePcmInput
import io.moduhome.app.core.voice.awaitOwnedPcmRecognition
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Each explicit request owns its microphone and sends PCM to one ON_DEVICE session. */
internal class PleosVoiceInputGateway(
    context: Context,
    private val inputFactory: () -> VoicePcmInput = { AndroidVoicePcmInput(context) },
) : VoiceInputGateway {
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
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext VoiceRecognitionResult.Unavailable("마이크 권한을 허용한 뒤 다시 눌러주세요")
        }
        if (active != null) return@withContext VoiceRecognitionResult.Busy
        val session = try {
            val input = inputFactory()
            Session(++nextSessionId, SpeechToText(context, Mode.ON_DEVICE), input)
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
        prepareRecognition(session)
        return awaitOwnedPcmRecognition(
            input = session.input,
            result = session.recognition,
            inputEnded = session.ended,
            onStarted = { withContext(Dispatchers.Main.immediate) {
                if (isCurrent(session) && !session.stopping && !session.recognition.isCompleted) {
                    debug(session, "capture_started")
                    mutableState.value = VoiceInputState.Listening()
                }
            } },
            sendFrame = { frame -> withContext(Dispatchers.Main.immediate) {
                if (isCurrent(session) && !session.stopping && !session.ended.isCompleted && !session.recognition.isCompleted) {
                    session.pcmSent = true
                    session.sdk.sendAudio(frame)
                }
            } },
            completeInput = { withContext(Dispatchers.Main.immediate) {
                if (isCurrent(session) && !session.stopping && session.pcmSent && !session.inputCompleted) {
                    session.inputCompleted = true
                    debug(session, "input_complete")
                    session.sdk.completeAudioSend()
                }
            } },
        )
    }

    private fun requestStop(session: Session) {
        if (!isCurrent(session)) return
        if (!session.stopping) debug(session, "capture_stop_requested")
        session.stopping = true
        session.input.stop()
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

    private fun prepareRecognition(session: Session) {
        if (!isCurrent(session) || session.stopping) return
        val listener = object : ResultListener {
            override fun onReady() = onMain {
                if (isCurrent(session)) debug(session, "sdk_ready")
            }
            override fun onStartedRecognition() = onMain {
                if (isCurrent(session)) debug(session, "started")
            }
            override fun onUpdated(stt: String, completed: Boolean) = onMain {
                if (!isCurrent(session) || session.stopping || !session.pcmSent || session.recognition.isCompleted) return@onMain
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
                if (isCurrent(session) && session.pcmSent) {
                    debug(session, "end")
                    if (!session.stopping && !session.recognition.isCompleted) {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            finish(session, VoiceRecognitionResult.Unavailable("차량 마이크에서 음성 입력을 시작하지 못했어요"))
        }
    }

    private fun finish(session: Session, result: VoiceRecognitionResult) {
        if (!isCurrent(session)) return
        session.recognition.complete(result)
    }

    private suspend fun shutdown(session: Session): String? {
        if (session.released) return session.shutdownFailure
        session.stopping = true
        session.initialization = null
        if (active === session) mutableState.value = VoiceInputState.Stopping
        var failure = if (session.input.isClosed) null else "차량 마이크 종료를 확인하지 못했어요. 터치로 계획을 선택해주세요"
        debug(session, if (session.input.isClosed) "capture_closed" else "capture_close_failed")
        if (session.pcmSent && !session.ended.isCompleted) {
            debug(session, "stop_requested")
            try {
                session.sdk.stop()
            } catch (_: Exception) {
                debug(session, "stop_failed")
                if (failure == null) failure = "차량 음성 연결을 종료하지 못했어요. 터치로 계획을 선택해주세요"
            }
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
            session.recognition.cancel()
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

    private class Session(val id: Long, val sdk: SpeechToText, val input: VoicePcmInput) {
        var initialization: CancellableContinuation<Initialization>? = null
        val recognition = CompletableDeferred<VoiceRecognitionResult>()
        var listener: ResultListener? = null
        var pcmSent = false
        var inputCompleted = false
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
    }
}
