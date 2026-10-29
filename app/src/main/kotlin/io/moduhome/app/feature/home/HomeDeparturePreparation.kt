package io.moduhome.app.feature.home

import io.moduhome.app.core.planning.DepartureClock
import io.moduhome.app.core.planning.ErrandJourneyPlan
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal sealed interface DepartureDraftResult {
    data class Ready(val draft: JourneyDraft) : DepartureDraftResult
    data class CalendarReview(val reason: String) : DepartureDraftResult
    data class Invalid(val reason: String) : DepartureDraftResult

    companion object {
        /** Called before comparison and again before departure; no road timestamps change. */
        fun prepare(draft: JourneyDraft, nowMillis: Long, zoneId: ZoneId): DepartureDraftResult {
            val clock = DepartureClock.at(nowMillis, zoneId)
                ?: return Invalid("출발 날짜와 시간을 확인해주세요")
            val hasDeadline = draft.pickup.deadline.isNotBlank() || draft.groceries.deadline.isNotBlank()
            if (hasDeadline && draft.clockZoneId != zoneId.id) {
                return CalendarReview("이전 마감의 시간대를 확인해주세요. 새 계획에서는 이전 마감을 지우고 다시 선택해요")
            }
            fun rebase(visit: VisitDraft): VisitDraft? {
                if (visit.deadline.isBlank()) return visit
                val minute = parseClockMinute(visit.deadline) ?: return null
                val offset = clock.deadlineDayOffset(draft.dateEpochDay, visit.deadlineNextDay) ?: return null
                val deadlineDate = LocalDate.ofEpochDay(clock.dateEpochDay).plusDays(offset.toLong())
                if (zoneId.rules.getValidOffsets(deadlineDate.atTime(minute / 60, minute % 60)).size != 1) return null
                return visit.copy(deadlineNextDay = offset == 1)
            }
            if (listOf(draft.pickup, draft.groceries).any { it.deadline.isNotBlank() && parseClockMinute(it.deadline) == null }) {
                return Invalid("볼일 마감을 시각 형식에 맞게 확인해주세요")
            }
            val pickup = rebase(draft.pickup)
            val groceries = rebase(draft.groceries)
            if (pickup == null || groceries == null) {
                return CalendarReview("날짜가 바뀌어 이전 마감을 그대로 사용할 수 없어요. 새 계획에서 마감을 다시 선택해주세요")
            }
            return Ready(draft.copy(
                dateEpochDay = clock.dateEpochDay,
                departure = String.format(Locale.ROOT, "%02d:%02d", clock.minuteOfDay / 60, clock.minuteOfDay % 60),
                clockZoneId = zoneId.id, pickup = pickup, groceries = groceries,
            ))
        }
    }
}

internal sealed interface HomeDepartureResult {
    data class Ready(val state: HomeUiState, val plan: ErrandJourneyPlan) : HomeDepartureResult
    data class Recompare(val state: HomeUiState, val reason: String) : HomeDepartureResult
    data class Refresh(val state: HomeUiState, val reason: String) : HomeDepartureResult
    data class CalendarReview(val reason: String) : HomeDepartureResult
    data class Rejected(val reason: String) : HomeDepartureResult

    companion object {
        fun prepare(state: HomeUiState, nowMillis: Long, zoneId: ZoneId): HomeDepartureResult {
            if (state.progress.stage != JourneyStage.REVIEW) return Rejected("선택한 방문 순서를 먼저 확인해주세요")
            val routing = state.routing ?: return Rejected("도로 경로를 다시 비교해주세요")
            if (!routing.matches(state.draft) || !routing.isFresh(nowMillis)) {
                return Refresh(state.copy(progress = JourneyProgress(), routing = null),
                    "경로 조회 후 5분이 지났거나 장소가 바뀌었어요. 현재 위치에서 다시 비교해주세요")
            }
            val draft = when (val result = DepartureDraftResult.prepare(state.draft, nowMillis, zoneId)) {
                is DepartureDraftResult.Ready -> result.draft
                is DepartureDraftResult.CalendarReview -> return CalendarReview(result.reason)
                is DepartureDraftResult.Invalid -> return Rejected(result.reason)
            }
            val next = state.copy(draft = draft)
            val plan = next.selectedPlan ?: return Rejected("선택한 방문 순서를 다시 확인해주세요")
            if (!plan.isValid) {
                return Recompare(next.copy(progress = next.progress.copy(stage = JourneyStage.COMPARE)),
                    "지금 출발하면 선택한 조건을 충족하지 못해요. 다른 순서나 마감을 확인해주세요")
            }
            return Ready(next, plan)
        }
    }
}
