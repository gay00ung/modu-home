package io.moduhome.app.feature.home

import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal sealed interface ProposalDraftResult {
    data class Applied(val draft: JourneyDraft) : ProposalDraftResult
    data class Invalid(val reason: String) : ProposalDraftResult
}

internal fun JourneyDraft.withProposal(proposal: ErrandProposal, zone: ZoneId = ZoneId.systemDefault()): ProposalDraftResult {
    val otherVisit = if (proposal.kind == VisitKind.PICKUP) groceries else pickup
    if (proposal.deadlineAtMillis != null && otherVisit.deadline.isNotBlank() && clockZoneId != zone.id) {
        return ProposalDraftResult.Invalid("이전 볼일 마감의 시간대를 확인한 뒤 새 계획에서 제안을 반영해주세요")
    }
    val deadline = proposal.deadlineAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val dayOffset = deadline?.toLocalDate()?.toEpochDay()?.minus(dateEpochDay)
    if (dayOffset != null && dayOffset !in 0L..1L) {
        return ProposalDraftResult.Invalid("제안 마감이 이번 출발일과 맞지 않아요. 출발일을 확인해주세요")
    }
    val visit = VisitDraft(
        enabled = true, place = proposal.place.name, stay = proposal.stayMinutes.toString(),
        walking = proposal.walkingMinutesOneWay.toString(), ready = proposal.ready,
        deadline = deadline?.let { "%02d:%02d".format(it.hour, it.minute) }.orEmpty(),
        deadlineNextDay = dayOffset == 1L,
    )
    return ProposalDraftResult.Applied(when (proposal.kind) {
        VisitKind.PICKUP -> copy(pickup = visit, navigationPlaces = navigationPlaces.copy(pickup = proposal.place),
            clockZoneId = if (otherVisit.deadline.isBlank()) zone.id else clockZoneId)
        VisitKind.GROCERIES -> copy(groceries = visit, navigationPlaces = navigationPlaces.copy(groceries = proposal.place),
            clockZoneId = if (otherVisit.deadline.isBlank()) zone.id else clockZoneId)
    })
}

internal fun JourneyDraft.proposal(
    kind: VisitKind, id: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault(),
): ErrandProposal? {
    val visit = if (kind == VisitKind.PICKUP) pickup else groceries
    val role = if (kind == VisitKind.PICKUP) NavigationPlaceRole.PICKUP else NavigationPlaceRole.GROCERIES
    val point = navigationPoint(role) ?: return null
    val stay = visit.stay.toIntOrNull()?.takeIf { it in 0..180 } ?: return null
    val walk = visit.walking.toIntOrNull()?.takeIf { it in 0..30 } ?: return null
    val deadline = if (visit.deadline.isBlank()) null else {
        val minute = parseClockMinute(visit.deadline) ?: return null
        LocalDate.ofEpochDay(dateEpochDay).plusDays(if (visit.deadlineNextDay) 1 else 0)
            .atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
    }
    return try {
        ErrandProposal(id, 1, nowMillis, nowMillis + ErrandProposal.MAX_LIFETIME_MILLIS,
            kind, point, stay, walk, visit.ready, deadline).takeIf { it.unavailableReason(nowMillis) == null }
    } catch (_: IllegalArgumentException) {
        null
    }
}
