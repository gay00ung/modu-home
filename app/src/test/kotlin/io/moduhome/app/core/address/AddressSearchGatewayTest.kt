package io.moduhome.app.core.address

import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.planning.VisitKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSearchGatewayTest {
    private val candidate = AddressCandidate.fromObservation("서울특별시 중구 세종대로 110", 37.5663, 126.9779)!!

    // JUnit is the blocking boundary; existing dependencies have no coroutines-test module.
    @Test fun preservesAddressAndCoordinatesWithoutInventingPoiIdentity() {
        assertEquals(candidate.address, candidate.point.address)
        assertEquals(37.5663, candidate.point.latitude, 0.0)
        assertEquals("", candidate.point.placeId)
        assertEquals("", candidate.point.subId)
        assertEquals("", candidate.point.provider)
        ErrandProposal("address_test", 1, 1_000, 2_000, VisitKind.PICKUP, candidate.point, 10)
    }

    @Test fun rejectsUnavailableNonFiniteOutOfRangeAndZeroCoordinates() {
        for ((lat, lon) in listOf(null to 127.0, 37.0 to null, Double.NaN to 127.0,
            37.0 to Double.POSITIVE_INFINITY, 90.01 to 127.0, 37.0 to -180.01, 0.0 to 0.0)) {
            assertNull(AddressCandidate.fromObservation("주소", lat, lon))
        }
        assertTrue(AddressCandidate.fromObservation("주소", 0.0, 127.0) != null)
    }

    @Test fun rejectsBlankLongMalformedAndControlCharacterAddresses() {
        for (address in listOf(null, "", "  ", "가".repeat(301), "서울\n주소", "서울\u007f", "서울\uD800")) {
            assertNull(AddressCandidate.fromObservation(address, 37.0, 127.0))
        }
    }

    @Test fun boundsThePointNameWithoutSplittingASurrogateOrLosingTheFullAddress() {
        val address = "가".repeat(119) + "\uD83D\uDE80" + "나".repeat(100)
        val result = AddressCandidate.fromObservation(address, 37.0, 127.0)!!
        assertEquals(address, result.address)
        assertEquals("가".repeat(119), result.point.name)
        ErrandProposal("address_test", 1, 1_000, 2_000, VisitKind.PICKUP, result.point, 10)
    }

    @Test fun rejectsInvalidQueriesBeforeStartingAnyBackendWork() = runBlocking {
        val backend = FakeBackend()
        val gateway = SingleFlightAddressSearchGateway(backend)
        for (query in listOf("", "  ", "가".repeat(301), "서울\t중구", "\uD800")) {
            assertTrue(gateway.search(query) is AddressSearchResult.Failed)
        }
        assertEquals(0, backend.requests.size)
    }

    @Test fun requestsAtMostFiveResultsAndReturnsTheExactCandidates() = runBlocking {
        val backend = FakeBackend()
        val gateway = SingleFlightAddressSearchGateway(backend)
        val first = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("  서울시청  ") }
        assertEquals("서울시청", backend.requests.single().query)
        assertEquals(5, backend.requests.single().maximum)
        val result = AddressSearchResult.Candidates(listOf(candidate))
        backend.complete(0, result)
        assertEquals(result, first.await())
    }

    @Test fun anotherSearchIsBusyUntilTheBackendCompletes() = runBlocking {
        val backend = FakeBackend()
        val gateway = SingleFlightAddressSearchGateway(backend)
        val first = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("서울시청") }
        repeat(3) { assertEquals(AddressSearchResult.Busy, gateway.search("다른 주소")) }
        assertEquals(1, backend.requests.size)
        backend.complete(0, AddressSearchResult.NoResults)
        assertEquals(AddressSearchResult.NoResults, first.await())
        val retry = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("다른 주소") }
        backend.complete(1, AddressSearchResult.NoResults)
        assertEquals(AddressSearchResult.NoResults, retry.await())
    }

    @Test fun timeoutDetachesTheWaiterButKeepsTheUnfinishedBackendSingleFlight() = runBlocking {
        val backend = FakeBackend()
        val gateway = SingleFlightAddressSearchGateway(backend, timeoutMillis = 20)
        assertEquals(AddressSearchResult.TimedOut, gateway.search("서울시청"))
        repeat(3) { assertEquals(AddressSearchResult.Busy, gateway.search("다른 주소")) }
        assertEquals(1, backend.requests.size)
        backend.complete(0, AddressSearchResult.Candidates(listOf(candidate)))
        val next = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("새 주소") }
        backend.complete(1, AddressSearchResult.NoResults)
        assertEquals(AddressSearchResult.NoResults, next.await())
    }

    @Test fun callerCancellationPropagatesAndLateCompletionOnlyReleasesItsLease() = runBlocking {
        val backend = FakeBackend()
        val gateway = SingleFlightAddressSearchGateway(backend)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("서울시청") }
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertEquals(AddressSearchResult.Busy, gateway.search("다른 주소"))
        backend.complete(0, AddressSearchResult.Candidates(listOf(candidate)))
        assertTrue(cancelled.isCancelled)
        val next = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("다른 주소") }
        backend.complete(1, AddressSearchResult.NoResults)
        assertEquals(AddressSearchResult.NoResults, next.await())
    }

    @Test fun duplicateAndOldCallbacksCannotCompleteOrUnlockANewerRequest() = runBlocking {
        val backend = FakeBackend()
        val gateway = SingleFlightAddressSearchGateway(backend)
        val first = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("첫 주소") }
        backend.complete(0, AddressSearchResult.NoResults)
        assertEquals(AddressSearchResult.NoResults, first.await())
        val next = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("둘째 주소") }
        backend.complete(0, AddressSearchResult.Candidates(listOf(candidate)))
        assertFalse(next.isCompleted)
        assertEquals(AddressSearchResult.Busy, gateway.search("셋째 주소"))
        backend.complete(1, AddressSearchResult.NoResults)
        backend.complete(1, AddressSearchResult.Failed("중복 오류"))
        assertEquals(AddressSearchResult.NoResults, next.await())
    }

    @Test fun errorCallbackReleasesTheLeaseAndCanArriveOffTheCallingThread() = runBlocking {
        val backend = FakeBackend()
        val gateway = SingleFlightAddressSearchGateway(backend)
        val request = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("서울시청") }
        val error = AddressSearchResult.Failed("검색 실패")
        withContext(Dispatchers.Default) { backend.complete(0, error) }
        assertEquals(error, request.await())
        val retry = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("서울시청") }
        backend.complete(1, AddressSearchResult.NoResults)
        assertEquals(AddressSearchResult.NoResults, retry.await())
    }

    @Test fun synchronousCallbacksAndStartExceptionsDoNotLeaveTheGatewayBusy() = runBlocking {
        var attempts = 0
        val gateway = SingleFlightAddressSearchGateway(AddressSearchBackend { _, _, callback ->
            if (attempts++ == 0) throw IllegalStateException("provider unavailable")
            callback(AddressSearchResult.NoResults)
        })
        assertTrue(gateway.search("서울시청") is AddressSearchResult.Failed)
        assertEquals(AddressSearchResult.NoResults, gateway.search("서울시청"))
        assertEquals(2, attempts)
    }

    @Test fun backendCancellationIsNotConvertedToAnOrdinaryFailure() = runBlocking {
        val gateway = SingleFlightAddressSearchGateway(AddressSearchBackend { _, _, _ -> throw CancellationException("cancel") })
        val request = async(start = CoroutineStart.UNDISPATCHED) { gateway.search("서울시청") }
        request.join()
        assertTrue(request.isCancelled)
    }

    @Test fun anOuterOwnerTimeoutIsNotSwallowedAsTheGatewaysOwnTimeout() = runBlocking {
        val backend = FakeBackend()
        val gateway = SingleFlightAddressSearchGateway(backend)
        var cancelled = false
        try {
            withTimeout(20) { gateway.search("서울시청") }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(AddressSearchResult.Busy, gateway.search("다른 주소"))
        backend.complete(0, AddressSearchResult.NoResults)
    }

    private class FakeBackend : AddressSearchBackend {
        data class Request(val query: String, val maximum: Int, val complete: (AddressSearchResult) -> Unit)
        val requests = mutableListOf<Request>()
        override fun request(address: String, maximumResults: Int, complete: (AddressSearchResult) -> Unit) {
            requests += Request(address, maximumResults, complete)
        }
        fun complete(index: Int, result: AddressSearchResult) { requests[index].complete(result) }
    }
}
