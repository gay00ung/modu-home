package io.moduhome.app.core.address

import io.moduhome.app.core.navigation.NavigationPoint
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal interface AddressSearchGateway {
    /** Explicit user search only; the caller owns its coroutine and cancellation. */
    suspend fun search(address: String): AddressSearchResult
}

internal data class AddressCandidate(val address: String, val point: NavigationPoint) {
    init {
        require(validAddressText(address, 300) && address.isNotBlank())
        require(validAddressText(point.name, 120) && point.address == address)
        require(point.latitude.isFinite() && point.longitude.isFinite())
        require(point.latitude != 0.0 || point.longitude != 0.0)
        require(point.placeId.isEmpty() && point.provider.isEmpty() && point.subId.isEmpty())
    }

    companion object {
        /** A geocoded address is not an identified POI or a verified vehicle entrance. */
        fun fromObservation(address: String?, latitude: Double?, longitude: Double?): AddressCandidate? {
            val line = address?.trim() ?: return null
            if (line.isBlank() || !validAddressText(line, 300) || latitude == null || longitude == null ||
                !latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 ||
                longitude !in -180.0..180.0 || (latitude == 0.0 && longitude == 0.0)) return null
            val prefix = line.take(120)
            val name = if (prefix.last().isHighSurrogate()) prefix.dropLast(1) else prefix
            return AddressCandidate(line, NavigationPoint(name, latitude, longitude, address = line))
        }
    }
}

internal sealed interface AddressSearchResult {
    data class Candidates(val items: List<AddressCandidate>) : AddressSearchResult {
        init { require(items.size in 1..MAX_ADDRESS_RESULTS) }
    }
    data object NoResults : AddressSearchResult
    data class Unavailable(val reason: String) : AddressSearchResult
    data object TimedOut : AddressSearchResult
    data object Busy : AddressSearchResult
    data class Failed(val reason: String) : AddressSearchResult
}

internal const val MAX_ADDRESS_RESULTS = 5

/**
 * No cancellation operation: Android Geocoder supplies none. A synchronous throw means the
 * request could not start; once accepted, completion/failure must arrive through complete.
 */
internal fun interface AddressSearchBackend {
    fun request(address: String, maximumResults: Int, complete: (AddressSearchResult) -> Unit)
}

/**
 * One unfinished backend request at a time. Timeout/cancellation detach the caller, not the
 * backend lease. A late completion releases that lease but cannot publish to the old caller.
 * An implementation that never calls back leaves this instance Busy; do not recreate the
 * gateway to bypass that bound. This class owns no scope, worker, or background job.
 */
internal class SingleFlightAddressSearchGateway(
    private val backend: AddressSearchBackend,
    private val timeoutMillis: Long = 10_000,
) : AddressSearchGateway {
    init { require(timeoutMillis > 0) }
    private val lock = Any()
    private var active: Pending? = null

    override suspend fun search(address: String): AddressSearchResult {
        currentCoroutineContext().ensureActive()
        val query = address.trim()
        if (query.isBlank() || !validAddressText(query, 300)) {
            return AddressSearchResult.Failed("검색할 주소를 300자 이내로 입력해주세요")
        }
        val pending = Pending()
        val acquired = synchronized(lock) {
            if (active != null) false else { active = pending; true }
        }
        if (!acquired) return AddressSearchResult.Busy
        return try {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<AddressSearchResult> { continuation ->
                    continuation.invokeOnCancellation {
                        synchronized(lock) {
                            pending.waiter = null
                            if (!pending.started && active === pending) active = null
                        }
                    }
                    val start = synchronized(lock) {
                        if (active !== pending || !continuation.isActive) false
                        else { pending.waiter = continuation; pending.started = true; true }
                    }
                    if (!start) return@suspendCancellableCoroutine
                    try {
                        backend.request(query, MAX_ADDRESS_RESULTS) { finish(pending, it) }
                    } catch (cancelled: CancellationException) {
                        detach(pending)?.cancel(cancelled)
                    } catch (_: Exception) {
                        finish(pending, AddressSearchResult.Failed("주소 검색을 시작하지 못했어요. 다시 시도해주세요"))
                    }
                }
            } ?: AddressSearchResult.TimedOut
        } finally {
            synchronized(lock) {
                pending.waiter = null
                if (!pending.started && active === pending) active = null
            }
        }
    }

    private fun finish(pending: Pending, result: AddressSearchResult) {
        detach(pending)?.resume(result)
    }

    private fun detach(pending: Pending): CancellableContinuation<AddressSearchResult>? = synchronized(lock) {
        if (active !== pending) return@synchronized null
        active = null
        pending.waiter.also { pending.waiter = null }
    }

    private class Pending(var waiter: CancellableContinuation<AddressSearchResult>? = null) {
        var started = false
    }
}

private fun validAddressText(value: String, maximum: Int): Boolean = value.length <= maximum &&
    value.none { it.isISOControl() || it == '\u007f' } &&
    value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value
