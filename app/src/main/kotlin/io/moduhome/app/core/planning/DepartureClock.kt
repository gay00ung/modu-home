package io.moduhome.app.core.planning

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Minute-based plans start at the next whole minute, preserving calendar deadlines. */
internal class DepartureClock private constructor(
    val dateEpochDay: Long,
    val minuteOfDay: Int,
    val zoneId: ZoneId,
) {
    fun deadlineDayOffset(originalDateEpochDay: Long, nextDay: Boolean): Int? {
        val deadlineDate = deadlineDate(originalDateEpochDay, nextDay) ?: return null
        return ChronoUnit.DAYS.between(LocalDate.ofEpochDay(dateEpochDay), deadlineDate).takeIf { it in 0L..1L }?.toInt()
    }

    companion object {
        fun deadlineDate(originalDateEpochDay: Long, nextDay: Boolean): LocalDate? = try {
            LocalDate.ofEpochDay(originalDateEpochDay).plusDays(if (nextDay) 1 else 0)
        } catch (_: DateTimeException) { null }

        fun at(nowMillis: Long, zoneId: ZoneId): DepartureClock? {
            if (nowMillis < 0) return null
            return try {
                val current = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
                val minute = current.truncatedTo(ChronoUnit.MINUTES).let {
                    if (current.second != 0 || current.nano != 0) it.plusMinutes(1) else it
                }
                // An ambiguous civil time cannot be represented by a single minute-of-day.
                if (zoneId.rules.getValidOffsets(minute.toLocalDateTime()).size != 1) return null
                val transition = zoneId.rules.nextTransition(minute.toInstant())
                if (transition != null && !transition.instant.isAfter(minute.toInstant().plus(48, ChronoUnit.HOURS))) return null
                DepartureClock(minute.toLocalDate().toEpochDay(), minute.hour * 60 + minute.minute, zoneId)
            } catch (_: DateTimeException) { null }
        }
    }
}
