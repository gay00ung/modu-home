package io.moduhome.app.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceReadbackPollingTest {
    @Test fun guidanceStartingAfterFiveSecondsIsReadBeforeTheSteadyInterval() {
        var elapsed = 0L
        repeat(8) {
            if (elapsed < 5_000L) elapsed += guidanceReadbackDelayMillis(elapsed, hasConfirmedGuidance = false)
        }
        assertTrue("A five-second native startup must not wait for the thirty-second poll", elapsed in 5_000L until 10_000L)
        assertEquals(30_000L, guidanceReadbackDelayMillis(elapsed, hasConfirmedGuidance = true))
    }

    @Test fun unavailableGuidanceCannotKeepAHighFrequencyLoopRunning() {
        val queries = mutableListOf<Long>()
        var elapsed = 0L
        while (elapsed < 60_000L && queries.size < 32) {
            queries += elapsed
            elapsed += guidanceReadbackDelayMillis(elapsed, hasConfirmedGuidance = false)
        }
        assertTrue("A missing readback must still advance the polling clock", elapsed >= 60_000L)
        assertTrue(queries.count { it < 15_000L } <= 8)
        assertTrue(queries.zipWithNext().all { (first, next) -> next - first >= 2_000L })
        assertTrue(queries.zipWithNext().any { (first, next) -> first >= 15_000L && next - first == 30_000L })
    }

    @Test fun laterFailuresDoNotRestartTheStartupBurst() {
        val firstInterval = guidanceReadbackDelayMillis(0, hasConfirmedGuidance = true)
        assertEquals(30_000L, firstInterval)
        assertEquals(30_000L, guidanceReadbackDelayMillis(firstInterval, hasConfirmedGuidance = false))
    }

    @Test fun invalidOrExhaustedElapsedTimeUsesTheSteadyInterval() {
        for (elapsed in listOf(-1L, Long.MIN_VALUE, 15_000L, Long.MAX_VALUE)) {
            assertEquals(30_000L, guidanceReadbackDelayMillis(elapsed, hasConfirmedGuidance = false))
        }
    }
}
