package io.moduhome.app.feature.home

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.*
import io.moduhome.app.data.JourneyJson
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HomeProposalAcceptanceTest {
    private val now = Instant.parse("2026-10-29T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val home = NavigationPoint("운전자 집", 37.5, 127.0)
    private val proposal = ErrandProposal("errand-001", 2, now - 1000, now + 3_600_000, VisitKind.PICKUP,
        NavigationPoint("커피 수령", 37.52, 127.02, "입구 2", "poi-id", "sub-id", "provider"),
        12, 3, false, now + 1_800_123)
    private val initial = HomeUiState(JourneyDraft(dateEpochDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay())
        .chooseNavigationPlace(NavigationPlaceRole.HOME, home))

    private fun inbox(value: ErrandProposal = proposal, status: ProposalReviewStatus = ProposalReviewStatus.PENDING) =
        JourneyProposalInbox.restore(listOf(ProposalInboxEntry(value, status)))

    private fun approve(state: HomeUiState = initial, source: JourneyProposalInbox = inbox(), at: Long = now): HomeProposalApplyResult =
        state.approveProposal(source, proposal.id, proposal.revision, at, zone)

    private fun applied() = approve() as HomeProposalApplyResult.Applied

    @Test fun acceptingPreservesHomeAndOtherErrandAndStoresOriginalConditions() {
        val result = applied()
        assertEquals(home, result.state.draft.navigationPlaces.home)
        assertEquals(initial.draft.groceries, result.state.draft.groceries)
        assertEquals(proposal.place, result.state.draft.navigationPlaces.pickup)
        assertEquals("12", result.state.draft.pickup.stay)
        assertEquals("3", result.state.draft.pickup.walking)
        assertFalse(result.state.draft.pickup.ready)
        assertEquals(listOf(HomeProposalAcceptance(proposal, now)), result.state.acceptedProposals)
        assertEquals(ProposalReviewStatus.APPROVED, result.inbox.entries.single().status)
        assertNull(result.state.routing)
        assertEquals(JourneyProgress(), result.state.progress)
    }

    @Test fun comparisonReviewAndActiveJourneyRejectApprovalWithoutChangingTheirInput() {
        JourneyStage.entries.filter { it != JourneyStage.INPUT }.forEach { stage ->
            assertTrue(approve(initial.copy(progress = JourneyProgress(stage))) is HomeProposalApplyResult.Rejected)
        }
        assertTrue(approve(initial.copy(progress = JourneyProgress(selectedPlanId = "old"))) is HomeProposalApplyResult.Rejected)
    }

    @Test fun staleRevisionChangedPayloadAndReviewedEntriesCannotBeAccepted() {
        assertTrue(approve(source = inbox(proposal.copy(revision = 3))) is HomeProposalApplyResult.Rejected)
        assertTrue(approve(source = inbox(status = ProposalReviewStatus.DECLINED)) is HomeProposalApplyResult.Rejected)
        val accepted = applied().state
        assertTrue(approve(accepted, inbox(proposal.copy(stayMinutes = 20))) is HomeProposalApplyResult.Rejected)
        assertTrue(approve(source = inbox(status = ProposalReviewStatus.APPROVED)) is HomeProposalApplyResult.Rejected)
    }

    @Test fun expiredSuggestionAndDeadlineOutsideTheJourneyDateAreRejected() {
        assertTrue(approve(at = proposal.expiresAtMillis) is HomeProposalApplyResult.Rejected)
        val wrongDate = initial.copy(draft = initial.draft.copy(dateEpochDay = initial.draft.dateEpochDay - 2))
        assertTrue(approve(wrongDate) is HomeProposalApplyResult.Rejected)
    }

    @Test fun aFailedJourneyWriteLeavesTheInboxPendingAndStopsBeforeAcknowledgement() {
        var inboxWrites = 0
        val outcome = HomeProposalCommitter({ false }, { inboxWrites++; true }).commit(applied())
        assertEquals(HomeProposalCommitResult.JOURNEY_FAILED, outcome)
        assertEquals(0, inboxWrites)
        assertEquals(ProposalReviewStatus.PENDING, inbox().entries.single().status)
    }

    @Test fun aFailedInboxWriteIsRecoverableFromTheDurableJourneyReceipt() {
        var stored: String? = null
        val outcome = HomeProposalCommitter({ state -> stored = JourneyJson.encode(state); stored != null }, { false }).commit(applied())
        assertEquals(HomeProposalCommitResult.INBOX_PENDING, outcome)
        val restored = JourneyJson.decode(requireNotNull(stored), initial.draft, now + 1)
        val repaired = restored.reconcileProposalInbox(inbox())
        assertEquals(ProposalReviewStatus.APPROVED, repaired.entries.single().status)
        assertEquals(proposal.place, restored.draft.navigationPlaces.pickup)
        assertEquals(applied().state, restored)
    }

    @Test fun processDeathBetweenWritesCannotLoseTheDraftOrApplyTheSuggestionTwice() {
        var stored: String? = null
        assertThrows(InterruptedException::class.java) {
            HomeProposalCommitter({ stored = JourneyJson.encode(it); true }, { throw InterruptedException("process ended") }).commit(applied())
        }
        val restored = JourneyJson.decode(requireNotNull(stored), initial.draft, now + 1)
        val manualEdit = restored.copy(draft = restored.draft.copy(pickup = restored.draft.pickup.copy(stay = "25")))
        val repeated = approve(manualEdit, inbox(), now + 2) as HomeProposalApplyResult.AlreadyApplied
        assertEquals(ProposalReviewStatus.APPROVED, repeated.inbox.entries.single().status)
        assertEquals("25", manualEdit.draft.pickup.stay)
        assertEquals(listOf(HomeProposalAcceptance(proposal, now)), manualEdit.acceptedProposals)
    }

    @Test fun successfulCommitStoresTheSameDraftBeforeItsApprovalReceipt() {
        var durable: HomeUiState? = null
        var acknowledged: JourneyProposalInbox? = null
        val change = applied()
        val result = HomeProposalCommitter({ durable = it; true }, {
            assertEquals(change.state, durable)
            acknowledged = it
            true
        }).commit(change)
        assertEquals(HomeProposalCommitResult.COMPLETE, result)
        assertEquals(change.inbox, acknowledged)
    }

    @Test fun reconciliationOnlyRepairsTheExactPendingVersion() {
        val accepted = applied().state
        assertEquals(ProposalReviewStatus.APPROVED, accepted.reconcileProposalInbox(inbox()).entries.single().status)
        val changed = inbox(proposal.copy(stayMinutes = 20))
        val newer = inbox(proposal.copy(revision = 3))
        val declined = inbox(status = ProposalReviewStatus.DECLINED)
        for (source in listOf(changed, newer, declined)) assertSame(source, accepted.reconcileProposalInbox(source))
    }

    @Test fun aNewerVersionRequiresAnotherExplicitApprovalAndReplacesOnlyItsHistoryEntry() {
        val original = applied().state
        val newer = proposal.copy(revision = 3, stayMinutes = 20)
        val result = original.approveProposal(inbox(newer), newer.id, newer.revision, now + 1, zone) as HomeProposalApplyResult.Applied
        assertEquals("20", result.state.draft.pickup.stay)
        assertEquals(listOf(HomeProposalAcceptance(newer, now + 1)), result.state.acceptedProposals)
    }

    @Test fun backwardsClockAndFutureReceiptsCannotApproveAnotherSuggestion() {
        assertTrue(approve(at = -1) is HomeProposalApplyResult.Rejected)
        assertTrue(approve(applied().state, at = now - 1) is HomeProposalApplyResult.Rejected)
    }

    @Test fun acceptingPrunesOnlyExpiredHistoryAndRefusesToDropLiveReceiptsAtCapacity() {
        val all = (0 until JourneyProposalInbox.MAX_ENTRIES).map { index ->
            HomeProposalAcceptance(proposal.copy(id = "history-$index"), now)
        }
        assertTrue(approve(initial.copy(acceptedProposals = all)) is HomeProposalApplyResult.Rejected)
        val expired = HomeProposalAcceptance(proposal.copy(id = "old-record", createdAtMillis = now - 10_000,
            expiresAtMillis = now - 1, deadlineAtMillis = null), now - 5000)
        val result = approve(initial.copy(acceptedProposals = all.dropLast(1) + expired)) as HomeProposalApplyResult.Applied
        assertEquals(JourneyProposalInbox.MAX_ENTRIES, result.state.acceptedProposals.size)
        assertFalse(result.state.acceptedProposals.any { it.proposal.id == expired.proposal.id })
    }

    @Test fun expiredHistoryCanRepairAnOldPendingReceiptWithoutReapplyingIt() {
        val accepted = applied().state
        val historical = approve(accepted, at = proposal.expiresAtMillis + 1)
        assertEquals(ProposalReviewStatus.APPROVED, (historical as HomeProposalApplyResult.AlreadyApplied).inbox.entries.single().status)
    }
}
