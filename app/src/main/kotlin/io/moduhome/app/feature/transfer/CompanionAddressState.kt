package io.moduhome.app.feature.transfer

import io.moduhome.app.core.address.AddressCandidate
import io.moduhome.app.core.planning.VisitKind

internal data class CompanionAddressState(
    val session: Long = 0L,
    val query: String = "",
    val candidates: List<AddressCandidate> = emptyList(),
    val selected: AddressCandidate? = null,
    val kind: VisitKind = VisitKind.PICKUP,
    val loading: Boolean = false,
    val message: String? = null,
    val resultQuery: String? = null,
) {
    val confirmedCandidate: AddressCandidate?
        get() = selected?.takeIf { !loading && resultQuery == query.trim() && it in candidates }

    fun clearResults(message: String? = null): CompanionAddressState =
        copy(candidates = emptyList(), selected = null, loading = false, resultQuery = null, message = message)

    override fun toString(): String = "CompanionAddressState(loading=$loading, address=redacted)"
}
