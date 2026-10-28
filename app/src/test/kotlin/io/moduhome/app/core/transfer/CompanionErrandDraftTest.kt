package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionErrandDraftTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val newYork = ZoneId.of("America/New_York")
    private val now = millis("2026-10-28T01:00:00Z")
    private val point = NavigationPoint(
        "픽업 매장", 37.5665, 126.9780, "서울 중구", "pickup-123", "entrance-b", "saved-place",
    )

    @Test fun openingTheEditorKeepsOriginalConditionsAndFixesTheCalendarBasis() {
        val original = proposal(deadline = now + 123_456)
        val draft = CompanionErrandDraft.from(original, now, seoul)

        assertSame(original, draft.original)
        assertEquals("5", draft.stayMinutes)
        assertEquals("3", draft.walkingMinutesOneWay)
        assertFalse(draft.ready)
        assertEquals(CompanionDeadlineChoice.KEEP_ORIGINAL, draft.deadline)
        assertEquals(LocalDate.of(2026, 10, 28), draft.baseDate)
        assertEquals(seoul, draft.zoneId)
    }

    @Test fun editingKeepsEveryPlaceFieldAndTheOriginalKindForEitherKind() {
        for (kind in VisitKind.entries) {
            val original = proposal(kind = kind)
            val draft = CompanionErrandDraft.from(original, now, seoul).copy(
                stayMinutes = "180", walkingMinutesOneWay = "30", ready = true,
            )
            val sent = created(draft)

            assertSame(original.place, sent.place)
            assertEquals(point, sent.place)
            assertEquals(kind, sent.kind)
            assertEquals(180, sent.stayMinutes)
            assertEquals(30, sent.walkingMinutesOneWay)
            assertTrue(sent.ready)
            assertEquals(5, original.stayMinutes)
            assertEquals(3, original.walkingMinutesOneWay)
            assertFalse(original.ready)
        }
    }

    @Test fun everyCreatedSuggestionHasANewUuidAndExactlyTwentyFourHoursFromIssuing() {
        val original = proposal(createdAt = now - 60_000)
        val draft = CompanionErrandDraft.from(original, now, seoul)
        val issuedAt = now + 15_001
        val first = created(draft, issuedAt)
        val second = created(draft, issuedAt)

        assertEquals(first.id, UUID.fromString(first.id).toString())
        assertFalse(original.id == first.id)
        assertFalse(first.id == second.id)
        assertEquals(1, first.revision)
        assertEquals(issuedAt, first.createdAtMillis)
        assertEquals(issuedAt + ErrandProposal.MAX_LIFETIME_MILLIS, first.expiresAtMillis)
    }

    @Test fun intermediateTextIsRetainedAndAllActiveFieldsAreValidatedAtCreation() {
        val draft = draft().copy(
            stayMinutes = "", walkingMinutesOneWay = "3.",
            deadline = CompanionDeadlineChoice.TODAY, deadlineTime = "18:",
        )
        assertEquals("", draft.stayMinutes)
        assertEquals("3.", draft.walkingMinutesOneWay)
        assertEquals("18:", draft.deadlineTime)
        assertEquals(setOf(CompanionErrandField.STAY, CompanionErrandField.WALKING,
            CompanionErrandField.DEADLINE), invalid(draft).errors.keys)
    }

    @Test fun minuteInputsRequireWholeAsciiNumbersWithinTheDomainBounds() {
        for (text in listOf("", " ", " 5", "5 ", "+1", "-0", "1.5", "１", "١", "2147483648")) {
            assertTrue(invalid(draft().copy(stayMinutes = text)).errors.containsKey(CompanionErrandField.STAY))
            assertTrue(invalid(draft().copy(walkingMinutesOneWay = text)).errors.containsKey(CompanionErrandField.WALKING))
        }
        assertTrue(invalid(draft().copy(stayMinutes = "181")).errors.containsKey(CompanionErrandField.STAY))
        assertTrue(invalid(draft().copy(walkingMinutesOneWay = "31")).errors.containsKey(CompanionErrandField.WALKING))
        val zero = created(draft().copy(stayMinutes = "0", walkingMinutesOneWay = "0"))
        assertEquals(0, zero.stayMinutes)
        assertEquals(0, zero.walkingMinutesOneWay)
        assertEquals(5, created(draft().copy(stayMinutes = "005")).stayMinutes)
    }

    @Test fun keepingTheOriginalDeadlinePreservesSubMinutePrecision() {
        val atMillis = now + 123_456
        val draft = draft(proposal(deadline = atMillis)).copy(deadlineTime = "not a clock")
        assertEquals(atMillis, created(draft).deadlineAtMillis)
    }

    @Test fun noDeadlineAndKeepingAnAbsentDeadlineIgnoreInactiveClockText() {
        assertNull(created(draft().copy(deadlineTime = "18:")).deadlineAtMillis)
        assertNull(created(draft(proposal(deadline = now + 60_000)).copy(
            deadline = CompanionDeadlineChoice.NONE, deadlineTime = "wrong",
        )).deadlineAtMillis)
    }

    @Test fun selectedDatesUseTheFixedDateAndAnExplicitTimeZone() {
        val today = created(draft().copy(deadline = CompanionDeadlineChoice.TODAY, deadlineTime = "18:10"))
        val tomorrow = created(draft().copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "00:05"))
        assertEquals(millis("2026-10-28T09:10:00Z"), today.deadlineAtMillis)
        assertEquals(millis("2026-10-28T15:05:00Z"), tomorrow.deadlineAtMillis)
    }

    @Test fun malformedOrOutOfRangeClockTextIsNotSilentlyNormalized() {
        for (text in listOf("", "1", "18:", "8:05", "18:5", "24:00", "18:60", "-1:00",
            "18:10:00", " 18:10", "18:10 ", "１８:１０", "18：10")) {
            assertTrue(invalid(draft().copy(deadline = CompanionDeadlineChoice.TOMORROW,
                deadlineTime = text)).errors.containsKey(CompanionErrandField.DEADLINE))
        }
        assertEquals(millis("2026-10-29T14:59:00Z"), created(draft().copy(
            deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "23:59",
        )).deadlineAtMillis)
    }

    @Test fun anExactDeadlineIsAllowedAndOneMillisecondAfterItIsPast() {
        val draft = draft().copy(deadline = CompanionDeadlineChoice.TODAY, deadlineTime = "10:00")
        assertEquals(now, created(draft).deadlineAtMillis)
        assertTrue(invalid(draft, now + 1).errors.containsKey(CompanionErrandField.DEADLINE))
    }

    @Test fun keepingAnExactOriginalDeadlineWorksButCannotRenewAPassedOne() {
        val draft = draft(proposal(deadline = now))
        assertEquals(now, created(draft).deadlineAtMillis)
        assertTrue(invalid(draft.copy(deadline = CompanionDeadlineChoice.NONE), now + 1)
            .errors.containsKey(CompanionErrandField.SOURCE))
    }

    @Test fun expiredSourcesCannotBeRenewedEvenWhenTheUserReplacesTheDeadline() {
        val original = proposal(expiresAt = now + 1_000)
        val draft = draft(original).copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "18:00")
        assertTrue(created(draft, now + 999).expiresAtMillis > original.expiresAtMillis)
        assertTrue(invalid(draft, now + 1_000).errors.containsKey(CompanionErrandField.SOURCE))
    }

    @Test fun sourceClockSkewUsesTheExistingFiveMinuteValidityRule() {
        val tolerated = draft(proposal(createdAt = now + 300_000))
        assertEquals(now, created(tolerated).createdAtMillis)
        assertTrue(invalid(draft(proposal(createdAt = now + 300_001)))
            .errors.containsKey(CompanionErrandField.SOURCE))
    }

    @Test fun theKeptDeadlineIsRecheckedAgainstTheNewIssuingInstant() {
        val source = proposal(createdAt = now + 300_000, deadline = now + 300_000 + 48 * HOUR)
        val draft = draft(source)
        assertTrue(invalid(draft).errors.containsKey(CompanionErrandField.DEADLINE))
        assertEquals(source.deadlineAtMillis, created(draft, now + 300_000).deadlineAtMillis)
        assertNull(created(draft.copy(deadline = CompanionDeadlineChoice.NONE)).deadlineAtMillis)
    }

    @Test fun midnightDoesNotMoveTheChosenDayForward() {
        val openedAt = millis("2026-10-28T14:58:00Z") // 23:58 in Seoul
        val afterMidnight = millis("2026-10-28T15:01:00Z")
        val draft = CompanionErrandDraft.from(proposal(createdAt = openedAt), openedAt, seoul)
        val today = draft.copy(deadline = CompanionDeadlineChoice.TODAY, deadlineTime = "23:59")
        val tomorrow = draft.copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "00:05")

        assertEquals(LocalDate.of(2026, 10, 28), draft.baseDate)
        assertTrue(invalid(today, afterMidnight).errors.containsKey(CompanionErrandField.DEADLINE))
        assertEquals(millis("2026-10-28T15:05:00Z"), created(tomorrow, afterMidnight).deadlineAtMillis)
    }

    @Test fun aClockReversalSinceOpeningRequiresReopeningTheEditor() {
        val original = proposal(createdAt = now - HOUR)
        val draft = draft(original)
        assertTrue(invalid(draft, now - 1).errors.containsKey(CompanionErrandField.CLOCK))
        assertEquals(now, created(draft, now).createdAtMillis)
    }

    @Test fun changingTheEditingTimeZoneRequiresReopeningInsteadOfReinterpretingTheClock() {
        assertTrue(invalid(draft().copy(deadline = CompanionDeadlineChoice.TODAY, deadlineTime = "18:00"),
            zone = ZoneId.of("UTC")).errors.containsKey(CompanionErrandField.CLOCK))
        assertTrue(invalid(draft().copy(baseDate = LocalDate.of(2026, 10, 29)))
            .errors.containsKey(CompanionErrandField.CLOCK))
    }

    @Test fun negativeClockValuesAreRejectedWithoutThrowingDuringEditingOrCreation() {
        val fromNegativeClock = CompanionErrandDraft.from(proposal(), -1, seoul)
        assertTrue(invalid(fromNegativeClock).errors.containsKey(CompanionErrandField.CLOCK))
        assertTrue(invalid(draft(), -1).errors.containsKey(CompanionErrandField.CLOCK))
    }

    @Test fun newExpiryCannotOverflowEvenWhenTheSourceIsStillValid() {
        val nearLimit = Long.MAX_VALUE - 1_000
        val original = proposal(createdAt = nearLimit, expiresAt = Long.MAX_VALUE)
        val draft = CompanionErrandDraft.from(original, nearLimit, seoul)
        assertTrue(invalid(draft, nearLimit).errors.containsKey(CompanionErrandField.CLOCK))
    }

    @Test fun daylightSavingGapIsRejectedWithoutMovingTheDeadlineToAnotherHour() {
        val openedAt = millis("2026-03-07T17:00:00Z")
        val draft = CompanionErrandDraft.from(proposal(createdAt = openedAt), openedAt, newYork)
            .copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "02:30")
        val failure = invalid(draft, openedAt, newYork)
        assertTrue(failure.errors.getValue(CompanionErrandField.DEADLINE).contains("존재하지 않는"))
    }

    @Test fun daylightSavingOverlapIsRejectedWhenTheInputCannotChooseAnOffset() {
        val openedAt = millis("2026-10-31T16:00:00Z")
        val draft = CompanionErrandDraft.from(proposal(createdAt = openedAt), openedAt, newYork)
            .copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "01:30")
        val failure = invalid(draft, openedAt, newYork)
        assertTrue(failure.errors.getValue(CompanionErrandField.DEADLINE).contains("두 번"))
    }

    @Test fun keepingAnAbsoluteDeadlineDoesNotRejectAnAlreadyResolvedDstOverlap() {
        val openedAt = millis("2026-10-31T16:00:00Z")
        val secondOccurrence = millis("2026-11-01T06:30:00Z")
        val original = proposal(createdAt = openedAt, deadline = secondOccurrence)
        val draft = CompanionErrandDraft.from(original, openedAt, newYork)
        assertEquals(secondOccurrence, created(draft, openedAt, newYork).deadlineAtMillis)
    }

    @Test fun theActualFortyEightHourLimitIncludesTheExtraDstHour() {
        val openedAt = millis("2026-10-31T04:00:00Z") // 00:00 before the 25-hour Sunday
        val draft = CompanionErrandDraft.from(proposal(createdAt = openedAt), openedAt, newYork)
            .copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "23:00")
        val atLimit = created(draft, openedAt, newYork)
        assertEquals(openedAt + 48 * HOUR, atLimit.deadlineAtMillis)
        assertTrue(invalid(draft.copy(deadlineTime = "23:01"), openedAt, newYork)
            .errors.containsKey(CompanionErrandField.DEADLINE))
    }

    @Test fun tomorrowUsesCalendarDaysWhileExpiryRemainsExactlyTwentyFourHoursAcrossDst() {
        val openedAt = millis("2026-03-07T05:00:00Z") // 00:00 before the 23-hour Sunday
        val draft = CompanionErrandDraft.from(proposal(createdAt = openedAt), openedAt, newYork)
            .copy(deadline = CompanionDeadlineChoice.TOMORROW, deadlineTime = "23:59")
        val sent = created(draft, openedAt, newYork)
        assertEquals(millis("2026-03-09T03:59:00Z"), sent.deadlineAtMillis)
        assertEquals(openedAt + 24 * HOUR, sent.expiresAtMillis)
    }

    private fun proposal(
        createdAt: Long = now,
        expiresAt: Long = createdAt + ErrandProposal.MAX_LIFETIME_MILLIS,
        deadline: Long? = null,
        kind: VisitKind = VisitKind.PICKUP,
    ) = ErrandProposal(
        id = "6f5164b6-af84-472a-a28e-d7dfed95d0c1", revision = 7,
        createdAtMillis = createdAt, expiresAtMillis = expiresAt,
        kind = kind, place = point, stayMinutes = 5, walkingMinutesOneWay = 3,
        ready = false, deadlineAtMillis = deadline,
    )

    private fun draft(original: ErrandProposal = proposal()) = CompanionErrandDraft.from(original, now, seoul)

    private fun created(draft: CompanionErrandDraft, at: Long = now, zone: ZoneId = seoul): ErrandProposal {
        val result = draft.createProposal(at, zone)
        assertTrue(result.toString(), result is CompanionErrandResult.Created)
        return (result as CompanionErrandResult.Created).proposal
    }

    private fun invalid(draft: CompanionErrandDraft, at: Long = now, zone: ZoneId = seoul): CompanionErrandResult.Invalid {
        val result = draft.createProposal(at, zone)
        assertTrue(result.toString(), result is CompanionErrandResult.Invalid)
        return result as CompanionErrandResult.Invalid
    }

    private fun millis(text: String): Long = Instant.parse(text).toEpochMilli()

    companion object {
        private const val HOUR = 60 * 60 * 1000L
    }
}
