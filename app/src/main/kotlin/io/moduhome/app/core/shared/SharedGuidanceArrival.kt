package io.moduhome.app.core.shared

import io.moduhome.app.core.navigation.GuidanceEstimate
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyStage
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Only the next unconfirmed stop is projected; a partial SDK window is never a full-trip ETA. */
internal class SharedGuidanceArrival private constructor(
    val point: NavigationPoint,
    val drivingArrivalAtMillis: Long,
    val readyAtMillis: Long,
    val plannedReadyAtMillis: Long?,
    val deadlineAtMillis: Long?,
    val observedAtMillis: Long,
) {
    val lateMinutes: Long get() = deadlineAtMillis?.let { minutesAbove(readyAtMillis, it) } ?: 0
    val planDelayMinutes: Long get() = plannedReadyAtMillis?.let { minutesAbove(readyAtMillis, it) } ?: 0

    val drivingMinutes: Long get() = minutesAbove(drivingArrivalAtMillis, observedAtMillis)

    companion object {
        fun fromObservation(
            plan: SharedJourneyPlan,
            progress: SharedJourneyProgress,
            estimate: GuidanceEstimate,
            departureAtMillis: Long,
            zoneId: ZoneId,
            nowMillis: Long,
        ): SharedGuidanceArrival? {
            if (progress.stage != JourneyStage.ACTIVE || !plan.isValid || plan.stops.size !in 1..3 || progress.selectedPlanId != plan.id ||
                progress.completedStopCount !in 0..plan.stops.size || departureAtMillis < 0 ||
                !estimate.isFresh(nowMillis)) return null
            val startedAt = progress.startedAtMillis ?: return null
            if (estimate.receivedAtEpochMillis < (progress.completedAtMillis.lastOrNull() ?: startedAt)) return null
            val home = progress.driverHome ?: return null
            val window = plan.currentWindow(home, progress.completedStopCount)
            if (progress.navigationPending || progress.confirmedWindow != window || !estimate.observedRoute.confirms(window)) return null
            val next = plan.stops.getOrNull(progress.completedStopCount)
            val point = next?.place?.point ?: home
            val native = estimate.waypoints.firstOrNull() ?: estimate.destination
            if (native.point != point) return null
            val terms = when (next) {
                is SharedScheduledStop.Dropoff -> ArrivalTerms(
                    next.candidate.stopMinutes + next.candidate.onward.totalMinutes,
                    plan.passengerArrivals.singleOrNull { it.passengerId == next.passenger.id }?.homeArrivalMinute,
                    next.passenger.latestArrivalMinute,
                )
                is SharedScheduledStop.Errand -> ArrivalTerms(
                    next.errand.visit.walkingMinutesOneWay + next.errand.visit.stayMinutes,
                    next.serviceFinishedMinute, next.errand.visit.deadlineMinute,
                )
                null -> ArrivalTerms(0, plan.driverHomeArrivalMinute, null)
            }
            return try {
                val arrival = Math.addExact(estimate.receivedAtEpochMillis, native.remainingSeconds.toLong() * 1_000)
                val ready = Math.addExact(arrival, terms.extraMinutes.toLong() * 60_000)
                val date = Instant.ofEpochMilli(departureAtMillis).atZone(zoneId).toLocalDate()
                val deadline = atJourneyMinute(date, zoneId, terms.deadlineMinute)
                if (terms.deadlineMinute != null && deadline == null) return null
                SharedGuidanceArrival(point, arrival, ready, atJourneyMinute(date, zoneId, terms.plannedMinute),
                    deadline, estimate.receivedAtEpochMillis)
            } catch (_: ArithmeticException) {
                null
            } catch (_: DateTimeException) {
                null
            }
        }

        private data class ArrivalTerms(val extraMinutes: Int, val plannedMinute: Int?, val deadlineMinute: Int?)

        private fun atJourneyMinute(date: LocalDate, zone: ZoneId, minute: Int?): Long? {
            if (minute == null || minute < 0) return null
            val local = date.atStartOfDay().plusMinutes(minute.toLong())
            val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
            return local.toInstant(offset).toEpochMilli().takeIf { it >= 0 }
        }

        private fun minutesAbove(value: Long, baseline: Long): Long {
            val difference = (value - baseline).coerceAtLeast(0)
            return difference / 60_000 + if (difference % 60_000 == 0L) 0 else 1
        }
    }
}
