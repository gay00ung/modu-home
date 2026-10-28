package io.moduhome.app.feature.transfer

import io.moduhome.app.core.address.AddressCandidate
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.*
import org.junit.Test

class CompanionAddressStateTest {
    private val candidate = AddressCandidate.fromObservation("서울특별시 중구 세종대로 110", 37.5663, 126.9779)!!
    private val found = CompanionAddressState(query = "세종대로 110", candidates = listOf(candidate),
        selected = candidate, resultQuery = "세종대로 110", kind = VisitKind.GROCERIES)

    @Test fun changingQueryCannotConfirmTheOldAddress() {
        assertEquals(candidate, found.confirmedCandidate)
        assertNull(found.copy(query = "세종대로 99").confirmedCandidate)
    }

    @Test fun changedCoordinatesRequireSelectingTheNewCandidate() {
        val moved = AddressCandidate.fromObservation(candidate.address, 37.57, 126.98)!!
        assertNull(found.copy(candidates = listOf(moved)).confirmedCandidate)
    }

    @Test fun searchingCannotConfirmPreviousResults() {
        assertNull(found.copy(loading = true).confirmedCandidate)
    }

    @Test fun cancelledSearchDropsItsSelectionButKeepsUserInput() {
        val cleared = found.clearResults("검색 중단")
        assertNull(cleared.confirmedCandidate)
        assertNull(cleared.selected)
        assertTrue(cleared.candidates.isEmpty())
        assertEquals(found.query, cleared.query)
        assertEquals(VisitKind.GROCERIES, cleared.kind)
    }
}
