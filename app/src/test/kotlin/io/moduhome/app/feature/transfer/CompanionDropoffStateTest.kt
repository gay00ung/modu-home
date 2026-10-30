package io.moduhome.app.feature.transfer

import androidx.compose.runtime.saveable.SaverScope
import io.moduhome.app.core.address.AddressCandidate
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.transfer.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CompanionDropoffStateTest {
    private val now = Instant.parse("2026-10-30T14:30:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val place = NavigationPoint("공개 장소", 37.5663, 126.9779, "서울특별시 중구 세종대로 110")
    private val scope = object : SaverScope { override fun canBeSaved(value: Any): Boolean = true }

    @Test fun unfinishedDropoffNameTimeAndAddressSurviveRecreationWithoutCredentials() {
        val draft = CompanionDropoffDraft.forAddress(place, now, zone).copy(passengerName = "친구 A",
            deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "01:")
        val saver = companionDropoffDraftSaver({ _, _ -> null }, { now + 1000 })
        val saved = with(saver) { scope.save(draft) }!!
        assertEquals(draft, saver.restore(saved))
        assertFalse((saved as List<*>).filterIndexed { index, _ -> index % 2 == 0 }.any { it.toString().contains("token", true) })
    }

    @Test fun receivedDropoffDraftNeedsTheSamePendingRevisionOnRestore() {
        val proposal = DropoffProposal("dropoff-001", 2, now, now + 3_600_000, place)
        val draft = CompanionDropoffDraft.from(proposal, now, zone)
        val saver = companionDropoffDraftSaver({ _, _ -> proposal }, { now + 1000 })
        val saved = with(saver) { scope.save(draft) }!!
        assertEquals(draft, saver.restore(saved))
        assertNull(companionDropoffDraftSaver({ _, _ -> proposal.copy(revision = 3) }, { now + 1000 }).restore(saved))
        assertNull(companionDropoffDraftSaver({ _, _ -> null }, { now + 1000 }).restore(saved))
    }

    @Test fun addressPurposeSurvivesRecreationAndOlderAddressDraftsRemainErrands() {
        val candidate = AddressCandidate.fromObservation(place.address, place.latitude, place.longitude)!!
        val state = CompanionAddressState(purpose = CompanionAddressPurpose.DROPOFF, query = place.address,
            candidates = listOf(candidate), selected = candidate, resultQuery = place.address)
        val saved = with(CompanionAddressStateSaver) { scope.save(state) }!!
        assertEquals(state, CompanionAddressStateSaver.restore(saved))
        val old = (saved as List<*>).chunked(2).filter { it[0] != "purpose" }.flatten()
        assertEquals(CompanionAddressPurpose.ERRAND, CompanionAddressStateSaver.restore(old)!!.purpose)
    }
}
