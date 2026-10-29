package io.moduhome.app.core.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedPcmRecognitionTest {
    private val recognized = VoiceRecognitionResult.Recognized("추가 20분")

    private class Input(
        val closeDelay: Long = 0,
        val closeConfirmed: Boolean = true,
        val body: suspend Input.(suspend (ByteArray) -> Unit) -> Unit = { stopped.await() },
    ) : VoicePcmInput {
        override var isClosed = true
        var started = false
        var stopCalls = 0
        val stopped = CompletableDeferred<Unit>()
        override fun stop() { stopCalls++; stopped.complete(Unit) }
        override suspend fun capture(onStarted: suspend () -> Unit, onFrame: suspend (ByteArray) -> Unit) {
            isClosed = false
            started = true
            try { onStarted(); body(onFrame) } finally {
                withContext(NonCancellable) { delay(closeDelay); isClosed = closeConfirmed }
            }
        }
    }

    @Test fun sdkInputEndStopsCaptureWhileTheFinalResultIsStillPending() = runTest {
        val input = Input(closeDelay = 10)
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred<Unit>()
        var completions = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedPcmRecognition(input, result, ended, {}, {}, { assertTrue(input.isClosed); completions++ })
        }
        ended.complete(Unit)
        runCurrent()
        assertTrue(input.stopCalls > 0)
        advanceTimeBy(10)
        runCurrent()
        assertTrue(input.isClosed)
        assertFalse(waiting.isCompleted)
        result.complete(recognized)
        assertEquals(recognized, waiting.await())
        assertEquals(1, completions)
    }

    @Test fun aFinalResultCannotEscapeBeforeMicrophoneClosureIsJoined() = runTest {
        val input = Input(closeDelay = 40)
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val ended = CompletableDeferred<Unit>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitOwnedPcmRecognition(input, result, ended, {}, {}, {}) }
        result.complete(recognized)
        runCurrent()
        advanceTimeBy(39)
        assertFalse(waiting.isCompleted)
        assertFalse(input.isClosed)
        advanceTimeBy(1)
        assertEquals(recognized, waiting.await())
        assertTrue(input.isClosed)
        assertFalse(ended.isCompleted)
    }

    @Test fun aFiniteInputIsCompletedOnceAfterClosingAndBeforeAwaitingTheDecoder() = runTest {
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val input = Input(body = { frame -> frame(byteArrayOf(1, 2)) })
        var frames = 0
        var completions = 0
        val outcome = awaitOwnedPcmRecognition(input, result, CompletableDeferred(), {}, { frames++ }, {
            assertTrue(input.isClosed)
            completions++
            result.complete(recognized)
        })
        assertEquals(recognized, outcome)
        assertEquals(1, frames)
        assertEquals(1, completions)
    }

    @Test fun callerCancellationClosesInputWithoutCompletingOrCancellingTheBackendResult() = runTest {
        val input = Input(closeDelay = 30)
        val result = CompletableDeferred<VoiceRecognitionResult>()
        var completions = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedPcmRecognition(input, result, CompletableDeferred(), {}, {}, { completions++ })
        }
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        assertTrue(input.isClosed)
        assertFalse(result.isCompleted)
        assertEquals(0, completions)
        assertEquals(30L, currentTime)
    }

    @Test fun captureCancellationPropagatesInsteadOfWaitingForTheRecognitionTimeout() = runTest {
        val input = Input(body = { throw CancellationException("capture cancelled") })
        val waiting = async {
            awaitOwnedPcmRecognition(input, CompletableDeferred(), CompletableDeferred(), {}, {}, {})
        }
        try { waiting.await(); fail("Cancellation must propagate") } catch (_: CancellationException) {
            assertTrue(input.isClosed)
            assertEquals(0L, currentTime)
        }
    }

    @Test fun backendCancellationAlsoJoinsMicrophoneClosure() = runTest {
        val input = Input(closeDelay = 20)
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedPcmRecognition(input, result, CompletableDeferred(), {}, {}, {})
        }
        result.cancel(CancellationException("backend cancelled"))
        try { waiting.await(); fail("Cancellation must propagate") } catch (_: CancellationException) {
            assertTrue(input.isClosed)
            assertEquals(20L, currentTime)
        }
    }

    @Test fun aReadFailureDoesNotCompleteAnInvalidAudioBuffer() = runTest {
        val input = Input(body = { throw IllegalStateException("read failed") })
        var completions = 0
        val outcome = awaitOwnedPcmRecognition(input, CompletableDeferred(), CompletableDeferred(), {}, {}, { completions++ })
        assertTrue(outcome is VoiceRecognitionResult.Unavailable)
        assertTrue(input.isClosed)
        assertEquals(0, completions)
    }

    @Test fun anExplicitStopClosesInputWithoutDecodingTheAbandonedBuffer() = runTest {
        val input = Input()
        val result = CompletableDeferred<VoiceRecognitionResult>()
        var completions = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedPcmRecognition(input, result, CompletableDeferred(), {}, {}, { completions++ })
        }
        result.complete(VoiceRecognitionResult.Stopped)
        assertEquals(VoiceRecognitionResult.Stopped, waiting.await())
        assertTrue(input.isClosed)
        assertEquals(0, completions)
    }

    @Test fun anAlreadyFailedRequestDoesNotOpenTheMicrophone() = runTest {
        val input = Input()
        val failure = VoiceRecognitionResult.Unavailable("unavailable")
        assertEquals(failure, awaitOwnedPcmRecognition(input, CompletableDeferred(failure), CompletableDeferred(), {}, {}, {}))
        assertFalse(input.started)
    }

    @Test fun framesQueuedAfterTheSdkEndpointAreNotForwarded() = runTest {
        val ended = CompletableDeferred<Unit>()
        val result = CompletableDeferred<VoiceRecognitionResult>()
        val input = Input(body = { frame ->
            frame(byteArrayOf(1, 2))
            ended.complete(Unit)
            frame(byteArrayOf(3, 4))
        })
        val frames = mutableListOf<ByteArray>()
        assertEquals(recognized, awaitOwnedPcmRecognition(input, result, ended, {}, { frames.add(it) }, { result.complete(recognized) }))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(1, 2), frames.single())
    }

    @Test fun unconfirmedClosureCannotReturnASuccessfulRecognition() = runTest {
        val input = Input(closeConfirmed = false)
        val result = CompletableDeferred<VoiceRecognitionResult>()
        var completions = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedPcmRecognition(input, result, CompletableDeferred(), {}, {}, { completions++ })
        }
        result.complete(recognized)
        assertTrue(waiting.await() is VoiceRecognitionResult.Unavailable)
        assertFalse(input.isClosed)
        assertEquals(0, completions)
    }

    @Test fun recognitionTimeoutStillStopsAndJoinsItsInput() = runTest {
        val input = Input(closeDelay = 20, body = { awaitCancellation() })
        assertEquals(VoiceRecognitionResult.NoSpeech,
            awaitOwnedPcmRecognition(input, CompletableDeferred(), CompletableDeferred(), {}, {}, {}, 1_000, 200))
        assertTrue(input.isClosed)
        assertEquals(1_020L, currentTime)
    }

    @Test fun aMissingFinalResultUsesTheGracePeriodAfterClosingInput() = runTest {
        val input = Input()
        val ended = CompletableDeferred<Unit>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedPcmRecognition(input, CompletableDeferred(), ended, {}, {}, {}, 1_000, 200)
        }
        advanceTimeBy(50)
        ended.complete(Unit)
        assertEquals(VoiceRecognitionResult.NoSpeech, waiting.await())
        assertTrue(input.isClosed)
        assertEquals(250L, currentTime)
    }

    @Test fun completionFailureReturnsUnavailableAfterReleasingInput() = runTest {
        val input = Input(body = {})
        assertTrue(awaitOwnedPcmRecognition(input, CompletableDeferred(), CompletableDeferred(), {}, {}, {
            throw IllegalStateException("completion failed")
        }) is VoiceRecognitionResult.Unavailable)
        assertTrue(input.isClosed)
    }
}
