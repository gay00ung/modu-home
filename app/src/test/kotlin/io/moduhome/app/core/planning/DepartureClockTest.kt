package io.moduhome.app.core.planning

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DepartureClockTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val date = LocalDate.of(2026, 10, 29)
    private val exact = Instant.parse("2026-10-29T09:00:00Z").toEpochMilli()

    @Test fun exactMinutesStayExactAndPartialMinutesRoundForward() {
        val first = requireNotNull(DepartureClock.at(exact, zone))
        val next = requireNotNull(DepartureClock.at(exact + 1, zone))
        assertEquals(date.toEpochDay(), first.dateEpochDay)
        assertEquals(1080, first.minuteOfDay)
        assertEquals(1081, next.minuteOfDay)
        assertEquals(zone, first.zoneId)
    }

    @Test fun roundingAcrossMidnightKeepsTheOriginalDeadlineDate() {
        val clock = requireNotNull(DepartureClock.at(Instant.parse("2026-10-29T14:59:59.999Z").toEpochMilli(), zone))
        assertEquals(date.plusDays(1).toEpochDay(), clock.dateEpochDay)
        assertEquals(0, clock.minuteOfDay)
        assertEquals(0, clock.deadlineDayOffset(date.toEpochDay(), true))
        assertNull(clock.deadlineDayOffset(date.toEpochDay(), false))
    }

    @Test fun pastOrDistantDeadlineDatesCannotBeMovedToAnotherDay() {
        val clock = requireNotNull(DepartureClock.at(exact, zone))
        assertEquals(0, clock.deadlineDayOffset(date.toEpochDay(), false))
        assertEquals(1, clock.deadlineDayOffset(date.toEpochDay(), true))
        assertNull(clock.deadlineDayOffset(date.minusDays(1).toEpochDay(), false))
        assertNull(clock.deadlineDayOffset(date.plusDays(2).toEpochDay(), false))
        assertNull(clock.deadlineDayOffset(Long.MAX_VALUE, true))
    }

    @Test fun negativeClockAndUnsupportedOffsetTransitionsCannotProduceAPlanClock() {
        assertNull(DepartureClock.at(-1, zone))
        val newYork = ZoneId.of("America/New_York")
        assertNull(DepartureClock.at(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), newYork))
        assertNull(DepartureClock.at(Instant.parse("2026-03-08T06:59:00Z").toEpochMilli(), newYork))
    }

    @Test fun deadlineDateHandlesTheYearBoundaryAndInvalidDates() {
        val last = LocalDate.of(2026, 12, 31)
        assertEquals(LocalDate.of(2027, 1, 1), DepartureClock.deadlineDate(last.toEpochDay(), true))
        assertNull(DepartureClock.deadlineDate(Long.MAX_VALUE, false))
    }
}
