package io.moduhome.app.core.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** The result cannot leave this call while its input is still being captured. */
internal suspend fun awaitOwnedPcmRecognition(
    input: VoicePcmInput,
    result: CompletableDeferred<VoiceRecognitionResult>,
    inputEnded: Deferred<Unit>,
    onStarted: suspend () -> Unit,
    sendFrame: suspend (ByteArray) -> Unit,
    completeInput: suspend () -> Unit,
    recognitionTimeoutMillis: Long = 15_000,
    finalResultGraceMillis: Long = 3_000,
): VoiceRecognitionResult = coroutineScope {
    if (result.isCompleted || inputEnded.isCompleted) {
        return@coroutineScope awaitVoiceRecognitionResult(result, inputEnded, recognitionTimeoutMillis, finalResultGraceMillis)
    }
    var completionAttempted = false
    suspend fun completeOnce() {
        if (!completionAttempted && input.isClosed) {
            if (result.isCompleted) {
                when (result.await()) {
                    is VoiceRecognitionResult.Unavailable, VoiceRecognitionResult.Stopped, VoiceRecognitionResult.Busy -> return
                    else -> Unit
                }
            }
            completionAttempted = true
            completeInput()
        }
    }
    val endpoint = launch(start = CoroutineStart.UNDISPATCHED) {
        inputEnded.await()
        input.stop()
    }
    val capture = async(start = CoroutineStart.UNDISPATCHED) {
        try {
            input.capture(onStarted) { frame ->
                if (!result.isCompleted && !inputEnded.isCompleted) sendFrame(frame)
            }
            completeOnce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result.complete(VoiceRecognitionResult.Unavailable("차량 마이크 입력을 처리하지 못했어요"))
        }
    }
    val response = async(start = CoroutineStart.UNDISPATCHED) {
        awaitVoiceRecognitionResult(result, inputEnded, recognitionTimeoutMillis, finalResultGraceMillis)
    }
    var cancelled = false
    val recognized: VoiceRecognitionResult = try {
        select<VoiceRecognitionResult> {
            response.onAwait { it }
            capture.onAwait { response.await() }
        }
    } catch (failure: CancellationException) {
        cancelled = true
        throw failure
    } finally {
        withContext(NonCancellable) {
            input.stop()
            capture.cancelAndJoin()
            endpoint.cancelAndJoin()
            response.cancelAndJoin()
            if (!cancelled) completeOnce()
        }
    }
    if (input.isClosed) recognized else VoiceRecognitionResult.Unavailable("차량 마이크 종료를 확인하지 못했어요")
}
