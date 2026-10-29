package io.moduhome.app.feature.transfer

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import io.moduhome.app.core.address.AddressCandidate
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.CompanionDeadlineChoice
import io.moduhome.app.core.transfer.CompanionErrandDraft
import io.moduhome.app.core.transfer.CompanionErrandResult
import io.moduhome.app.core.transfer.ErrandProposal
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CompanionStateSaversTest {
    private val opened = Instant.parse("2026-10-28T14:59:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val place = NavigationPoint("공개 장소", 37.5663, 126.9779, "서울특별시 중구 세종대로 110")
    private val scope = object : SaverScope { override fun canBeSaved(value: Any): Boolean = true }

    private fun <T> save(saver: Saver<T, Any>, value: T): Any = with(saver) { requireNotNull(scope.save(value)) }
    private fun addressDraft() = CompanionErrandDraft.forAddress(place, VisitKind.GROCERIES, opened, zone)
    private fun original() = ErrandProposal("00000000-0000-4000-8000-000000000001", 2, opened,
        opened + ErrandProposal.MAX_LIFETIME_MILLIS, VisitKind.PICKUP, place, 12, 3, true, opened + 3_600_123)
    private fun saver(now: Long = opened + 120_000, proposal: ErrandProposal? = null) = companionDraftSaver(
        received = { id, revision -> proposal?.takeIf { it.id == id && it.revision == revision } },
        nowMillis = { now },
    )

    @Test fun addressDraftKeepsSourceClockAndInvalidEdits() {
        val draft = addressDraft().copy(stayMinutes = "", walkingMinutesOneWay = "abc", deadlineTime = "9:")
        val saver = saver()
        assertEquals(draft, saver.restore(save(saver, draft)))
    }

    @Test fun restoredTomorrowKeepsTheDateChosenBeforeMidnight() {
        val draft = addressDraft().copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "00:30")
        val saver = saver()
        val restored = requireNotNull(saver.restore(save(saver, draft)))
        val result = restored.createProposal(opened + 120_000, zone) as CompanionErrandResult.Created
        assertEquals(Instant.parse("2026-10-28T15:30:00Z").toEpochMilli(), result.proposal.deadlineAtMillis)
        assertEquals(draft.baseDate, restored.baseDate)
        assertEquals(opened, restored.openedAtMillis)
    }

    @Test fun receivedDraftResolvesTheExactInboxRevisionAndOriginalDeadline() {
        val proposal = original()
        val draft = CompanionErrandDraft.from(proposal, opened, zone).copy(stayMinutes = "7", ready = false)
        val saver = saver(proposal = proposal)
        val encoded = save(saver, draft)
        assertFalse(encoded.toString().contains(place.address))
        val restored = requireNotNull(saver.restore(encoded))
        assertEquals(draft, restored)
        val created = restored.createProposal(opened + 120_000, zone) as CompanionErrandResult.Created
        assertEquals(proposal.deadlineAtMillis, created.proposal.deadlineAtMillis)
        assertEquals(7, created.proposal.stayMinutes)
        assertFalse(created.proposal.ready)
    }

    @Test fun missingOrSupersededInboxRevisionDoesNotRecreateTheSource() {
        val draft = CompanionErrandDraft.from(original(), opened, zone)
        val encoded = save(saver(proposal = original()), draft)
        assertNull(saver().restore(encoded))
        assertNull(saver(proposal = original().copy(revision = 3)).restore(encoded))
    }

    @Test fun expiredReceivedSourceCannotBeRestored() {
        val proposal = original()
        val encoded = save(saver(proposal = proposal), CompanionErrandDraft.from(proposal, opened, zone))
        assertNull(saver(now = proposal.deadlineAtMillis!! + 1, proposal = proposal).restore(encoded))
    }

    @Test fun restorationDoesNotRenewAddressDraftLifetimeOrAcceptClockRollback() {
        val encoded = save(saver(), addressDraft())
        assertNull(saver(now = opened + ErrandProposal.MAX_LIFETIME_MILLIS).restore(encoded))
        assertNull(saver(now = opened - 1).restore(encoded))
    }

    @Test fun changedClockAnchorAndMalformedStateFailClosed() {
        val encoded = (save(saver(), addressDraft()) as List<*>).toMutableList()
        encoded[encoded.indexOf("date") + 1] = addressDraft().baseDate.plusDays(1).toEpochDay()
        assertNull(saver().restore(encoded))
        assertNull(saver().restore(arrayListOf("version", 99)))
        assertNull(saver().restore(arrayListOf("version", 1)))
    }

    @Test fun emptyEditorsRemainEmpty() {
        val saver = saver()
        assertNull(with(saver) { scope.save(null) })
        assertNull(with(CompanionAddressStateSaver) { scope.save(null) })
        assertNull(saver.restore(arrayListOf<Any>()))
        assertNull(CompanionAddressStateSaver.restore(arrayListOf<Any>()))
    }

    @Test fun completedAddressSelectionSurvivesRecreation() {
        val first = requireNotNull(AddressCandidate.fromObservation("서울특별시 중구 세종대로 110", 37.5663, 126.9779))
        val second = requireNotNull(AddressCandidate.fromObservation("서울특별시 용산구 한강대로 405", 37.5551, 126.9707))
        val state = CompanionAddressState(session = 7, query = " 한강대로 405 ", candidates = listOf(first, second),
            selected = second, kind = VisitKind.GROCERIES, resultQuery = "한강대로 405")
        val restored = requireNotNull(CompanionAddressStateSaver.restore(save(CompanionAddressStateSaver, state)))
        assertEquals(state.copy(session = 0), restored)
        assertEquals(second, restored.confirmedCandidate)
    }

    @Test fun interruptedSearchKeepsTheQueryWithoutRestoringAJobOrSelection() {
        val point = requireNotNull(AddressCandidate.fromObservation(place.address, place.latitude, place.longitude))
        val state = CompanionAddressState(query = "세종대로 110", candidates = listOf(point), selected = point,
            kind = VisitKind.GROCERIES, loading = true, resultQuery = "세종대로 110")
        val restored = requireNotNull(CompanionAddressStateSaver.restore(save(CompanionAddressStateSaver, state)))
        assertEquals(state.query, restored.query)
        assertEquals(state.kind, restored.kind)
        assertFalse(restored.loading)
        assertTrue(restored.candidates.isEmpty())
        assertNull(restored.confirmedCandidate)
        assertNotNull(restored.message)
    }

    @Test fun changedQueryDoesNotRestoreUnrelatedCandidates() {
        val point = requireNotNull(AddressCandidate.fromObservation(place.address, place.latitude, place.longitude))
        val state = CompanionAddressState(query = "다른 주소", candidates = listOf(point), selected = point, resultQuery = "세종대로 110")
        val restored = requireNotNull(CompanionAddressStateSaver.restore(save(CompanionAddressStateSaver, state)))
        assertEquals("다른 주소", restored.query)
        assertTrue(restored.candidates.isEmpty())
        assertNull(restored.confirmedCandidate)
    }

    @Test fun invalidAddressStateCannotRestoreASelection() {
        assertNull(CompanionAddressStateSaver.restore(arrayListOf("version", 1)))
        assertNull(CompanionAddressStateSaver.restore(arrayListOf("version", 99)))
    }
}
