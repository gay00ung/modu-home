package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CompanionDropoffDraftTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-10-30T14:30:00Z").toEpochMilli()
    private val draft = CompanionDropoffDraft.forAddress(NavigationPoint("하차지", 37.56, 126.98), now, zone)

    @Test fun nameAndArrivalAreOptionalAndOnlyCreationIssuesAProposal() {
        val created = draft.createProposal(now + 1, zone) as CompanionDropoffResult.Created
        assertEquals("동승자", created.proposal.passengerName)
        assertNull(created.proposal.deadlineAtMillis)
        assertEquals(draft.place, created.proposal.place)
    }

    @Test fun tomorrowRefersToTheCapturedDateEvenAfterMidnight() {
        val edited = draft.copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "01:00")
        val created = edited.createProposal(now + 60 * 60 * 1000, zone) as CompanionDropoffResult.Created
        assertEquals(Instant.parse("2026-10-30T16:00:00Z").toEpochMilli(), created.proposal.deadlineAtMillis)
    }

    @Test fun pastTimeMalformedInputClockRollbackAndTimezoneChangesCannotBeSent() {
        assertTrue(draft.copy(deadline = CompanionDeadlineChoice.TODAY, deadlineTime = "21:00")
            .createProposal(now, zone) is CompanionDropoffResult.Invalid)
        assertTrue(draft.copy(deadline = CompanionDeadlineChoice.TODAY, deadlineTime = "24:60")
            .createProposal(now, zone) is CompanionDropoffResult.Invalid)
        assertTrue(draft.createProposal(now - 1, zone) is CompanionDropoffResult.Invalid)
        assertTrue(draft.createProposal(now, ZoneId.of("UTC")) is CompanionDropoffResult.Invalid)
        assertTrue(draft.createProposal(now + JourneyProposal.MAX_LIFETIME_MILLIS, zone) is CompanionDropoffResult.Invalid)
        assertTrue(draft.copy(passengerName = "이름\n다른 줄").createProposal(now, zone) is CompanionDropoffResult.Invalid)
    }

    @Test fun anExpiredReceivedSuggestionCannotBeRenewedFromTheEditor() {
        val source = DropoffProposal("dropoff-001", 1, now, now + 1_000, draft.place)
        val received = CompanionDropoffDraft.from(source, now, zone)
        assertTrue(received.createProposal(now + 1_000, zone) is CompanionDropoffResult.Invalid)
    }
}
