package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposal
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ProposalDraftMappingTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val date = LocalDate.of(2026, 10, 28)
    private val now = date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    private val point = NavigationPoint("테스트 픽업", 37.5, 127.0)
    private fun proposal(deadline: Long? = null) = ErrandProposal("proposal_01", 1, now, now + 3_600_000,
        VisitKind.PICKUP, point, 5, 2, true, deadline)

    @Test fun `receiving pickup preserves home and other errand and binds real point`() {
        val initial = JourneyDraft(dateEpochDay = date.toEpochDay())
        val result = initial.withProposal(proposal(), zone) as ProposalDraftResult.Applied
        assertEquals(initial.home, result.draft.home)
        assertEquals(initial.groceries, result.draft.groceries)
        assertEquals(point, result.draft.navigationPoint(NavigationPlaceRole.PICKUP))
        assertTrue(result.draft.pickup.ready)
        assertEquals("2", result.draft.pickup.walking)
    }

    @Test fun `next day deadline is preserved as next day`() {
        val deadline = date.plusDays(1).atTime(1, 30).atZone(zone).toInstant().toEpochMilli()
        val result = JourneyDraft(dateEpochDay = date.toEpochDay()).withProposal(proposal(deadline), zone) as ProposalDraftResult.Applied
        assertEquals("01:30", result.draft.pickup.deadline)
        assertTrue(result.draft.pickup.deadlineNextDay)
    }

    @Test fun `deadline outside departure date does not silently change journey date`() {
        val wrongDay = JourneyDraft(dateEpochDay = date.minusDays(2).toEpochDay())
        assertTrue(wrongDay.withProposal(proposal(now + 60_000), zone) is ProposalDraftResult.Invalid)
    }

    @Test fun `export requires a bound place rather than a typed label`() {
        assertNull(JourneyDraft(dateEpochDay = date.toEpochDay()).proposal(VisitKind.PICKUP, "proposal_01", now, zone))
    }

    @Test fun `export preserves ready state and rejects malformed visit time`() {
        val draft = JourneyDraft(dateEpochDay = date.toEpochDay()).chooseNavigationPlace(NavigationPlaceRole.PICKUP, point)
        val ready = draft.copy(pickup = draft.pickup.copy(ready = true))
        assertTrue(ready.proposal(VisitKind.PICKUP, "proposal_01", now, zone)!!.ready)
        assertNull(ready.copy(pickup = ready.pickup.copy(stay = "oops")).proposal(VisitKind.PICKUP, "proposal_01", now, zone))
    }
}
