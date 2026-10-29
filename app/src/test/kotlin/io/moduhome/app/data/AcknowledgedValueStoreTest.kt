package io.moduhome.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AcknowledgedValueStoreTest {
    @Test fun aFailedCommitCannotExposeTheOptimisticBackendCacheToAnotherReader() {
        var cache = "old"
        var writes = 0
        val store = AcknowledgedValueStore("old") { next ->
            if (writes++ == 0) cache = next
            false // Even rollback fails before it can repair this backend cache.
        }
        assertFalse(store.save("unwritten"))
        assertEquals("unwritten", cache)
        assertEquals("old", store.load())
        val recreatedScreenRead = store::load
        assertEquals("old", recreatedScreenRead())
    }

    @Test fun aSuccessfulRollbackDoesNotTurnTheRejectedRequestIntoASuccess() {
        var cache = "old"
        var disk = "old"
        var writes = 0
        val store = AcknowledgedValueStore("old") { next ->
            cache = next
            (++writes > 1).also { if (it) disk = next }
        }
        assertFalse(store.save("unwritten"))
        assertEquals("old", cache)
        assertEquals("old", disk)
        assertEquals("old", store.load())
    }

    @Test fun retryOnlyPublishesTheValueAfterTheBackendAcknowledgesIt() {
        var canWrite = false
        var disk = "old"
        val store = AcknowledgedValueStore("old") { next -> canWrite.also { if (it) disk = next } }
        assertFalse(store.save("new"))
        assertEquals("old", store.load())
        canWrite = true
        assertTrue(store.save("new"))
        assertEquals("new", disk)
        assertEquals("new", store.load())
    }

    @Test fun aWriteExceptionAndAnUnrepairableCacheStillKeepTheAcknowledgedValue() {
        var cache = "old"
        var attempts = 0
        val store = AcknowledgedValueStore("old") { next ->
            if (attempts++ == 0) cache = next
            throw IOException("storage unavailable")
        }
        assertFalse(store.save("unwritten"))
        assertEquals("unwritten", cache)
        assertEquals("old", store.load())
        assertEquals(2, attempts)
    }

    @Test fun anAbsentInitialSessionIsNotCreatedByAFailedWrite() {
        var cache: String? = null
        var writes = 0
        val store = AcknowledgedValueStore<String?>(null) { next ->
            cache = next
            ++writes > 1
        }
        assertFalse(store.save("unwritten"))
        assertNull(store.load())
        assertNull(cache)
    }

    @Test fun aFailedModeWriteKeepsThePreviousFlagEvenIfRollbackAlsoFails() {
        var cache = true
        var writes = 0
        val store = AcknowledgedValueStore(true) { next ->
            if (writes++ == 0) cache = next
            false
        }
        assertFalse(store.save(false))
        assertFalse(cache)
        assertTrue(store.load())
    }

    @Test fun savingAnUnchangedValueStillRequiresAcknowledgement() {
        var writes = 0
        val store = AcknowledgedValueStore("same") { writes++; false }
        assertFalse(store.save("same"))
        assertEquals("same", store.load())
        assertEquals(2, writes)
    }
}
