package io.moduhome.app.core.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecognitionCompletionTest {
    private val recognized = VoiceRecognitionResult.Recognized("추가 20분")

    @Test fun finalResultDoesNotPretendTheMicrophoneHasEnded() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred<Unit>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitVoiceRecognitionResult(result, ended) }
        result.complete(recognized)
        assertEquals(recognized, waiting.await())
        assertFalse(ended.isCompleted)
    }

    @Test fun inputEndLeavesTimeForTheFinalDecoderResult() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred<Unit>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitVoiceRecognitionResult(result, ended, 1_000, 200)
        }
        ended.complete(Unit)
        runCurrent()
        advanceTimeBy(199)
        assertFalse(waiting.isCompleted)
        result.complete(recognized)
        assertEquals(recognized, waiting.await())
        assertEquals(199L, currentTime)
    }

    @Test fun anAlreadyAvailableFinalResultWinsOverAnInputEnd() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>(recognized)
        val ended = CompletableDeferred(Unit)
        assertEquals(recognized, awaitVoiceRecognitionResult(result, ended, 1_000, 200))
        assertEquals(0L, currentTime)
    }

    @Test fun anEndedInputWithoutAFinalResultWaitsOnlyForTheGracePeriod() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred(Unit)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitVoiceRecognitionResult(result, ended, 1_000, 200)
        }
        advanceTimeBy(199)
        assertFalse(waiting.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(VoiceRecognitionResult.NoSpeech, waiting.await())
        assertEquals(200L, currentTime)
        assertFalse(result.isCompleted)
    }

    @Test fun theOverallTimeoutStillBoundsAServiceThatNeverEnds() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred<Unit>()
        assertEquals(VoiceRecognitionResult.NoSpeech, awaitVoiceRecognitionResult(result, ended, 1_000, 200))
        assertEquals(1_000L, currentTime)
        assertFalse(ended.isCompleted)
    }

    @Test fun anExplicitStopDuringTheGracePeriodReturnsImmediately() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred(Unit)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitVoiceRecognitionResult(result, ended, 1_000, 200)
        }
        advanceTimeBy(30)
        result.complete(VoiceRecognitionResult.Stopped)
        assertEquals(VoiceRecognitionResult.Stopped, waiting.await())
        assertEquals(30L, currentTime)
    }

    @Test fun callerCancellationIsNotTurnedIntoNoSpeech() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred(Unit)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitVoiceRecognitionResult(result, ended, 1_000, 200)
        }
        advanceTimeBy(30)
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        assertFalse(result.isCancelled)
        assertEquals(30L, currentTime)
    }

    @Test fun theCallersTimeoutIsNotSwallowedByAnInnerTimeout() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred(Unit)
        try {
            withTimeout(40) { awaitVoiceRecognitionResult(result, ended, 1_000, 200) }
            fail("The caller timeout must propagate")
        } catch (_: TimeoutCancellationException) {
            assertEquals(40L, currentTime)
        }
    }

    @Test fun backendCancellationRemainsCancellation() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred<Unit>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitVoiceRecognitionResult(result, ended, 1_000, 200)
        }
        result.cancel(CancellationException("service cancelled"))
        try {
            waiting.await()
            fail("Backend cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(waiting.isCancelled)
        }
    }
}
