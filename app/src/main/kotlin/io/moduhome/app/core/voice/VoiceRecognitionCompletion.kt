package io.moduhome.app.core.voice

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Input can end before decoding finishes; cancellation and both waits belong to the caller. */
internal suspend fun awaitVoiceRecognitionResult(
    result: Deferred<VoiceRecognitionResult>,
    inputEnded: Deferred<Unit>,
    recognitionTimeoutMillis: Long = 15_000,
    finalResultGraceMillis: Long = 3_000,
): VoiceRecognitionResult {
    require(recognitionTimeoutMillis > 0 && finalResultGraceMillis > 0)
    return withTimeoutOrNull(recognitionTimeoutMillis) {
        select {
            result.onAwait { it }
            inputEnded.onAwait {
                withTimeoutOrNull(finalResultGraceMillis) { result.await() } ?: VoiceRecognitionResult.NoSpeech
            }
        }
    } ?: VoiceRecognitionResult.NoSpeech
}
