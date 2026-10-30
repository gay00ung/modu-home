package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

internal data class CompanionDropoffDraft(
    val place: NavigationPoint,
    val openedAtMillis: Long,
    val baseDate: LocalDate,
    val zoneId: ZoneId,
    val passengerName: String = "",
    val deadline: CompanionDeadlineChoice = CompanionDeadlineChoice.NONE,
    val deadlineTime: String = "",
    val source: DropoffProposal? = null,
    val issuedId: String? = null,
    val issuedRevision: Int = 0,
) {
    fun createProposal(nowMillis: Long, zoneId: ZoneId): CompanionDropoffResult {
        if (openedAtMillis < 0 || nowMillis < openedAtMillis || this.zoneId != zoneId ||
            nowMillis > Long.MAX_VALUE - JourneyProposal.MAX_LIFETIME_MILLIS ||
            nowMillis - openedAtMillis >= JourneyProposal.MAX_LIFETIME_MILLIS ||
            baseDate != Instant.ofEpochMilli(openedAtMillis).atZone(zoneId).toLocalDate()) {
            return invalid(CompanionDropoffField.CLOCK, "기기 시각과 시간대를 확인하고 하차지를 다시 열어주세요")
        }
        source?.unavailableReason(nowMillis)?.let { return invalid(CompanionDropoffField.CLOCK, it) }
        val name = passengerName.trim().ifBlank { "동승자" }
        if (name.length > 32 || name.any { it.isISOControl() }) {
            return invalid(CompanionDropoffField.NAME, "이름은 줄바꿈 없이 32자까지 입력해주세요")
        }
        if (deadline == CompanionDeadlineChoice.KEEP_ORIGINAL && source == null) {
            return invalid(CompanionDropoffField.ARRIVAL, "받은 도착 시간을 다시 확인해주세요")
        }
        val at = when (deadline) {
            CompanionDeadlineChoice.NONE -> null
            CompanionDeadlineChoice.KEEP_ORIGINAL -> source?.deadlineAtMillis
            CompanionDeadlineChoice.TODAY, CompanionDeadlineChoice.TOMORROW -> {
                val time = runCatching {
                    require(deadlineTime.matches(Regex("[0-9]{2}:[0-9]{2}")))
                    LocalTime.parse(deadlineTime)
                }.getOrNull() ?: return invalid(CompanionDropoffField.ARRIVAL, "도착 시각을 21:30 형식으로 입력해주세요")
                val local = baseDate.plusDays(if (deadline == CompanionDeadlineChoice.TOMORROW) 1 else 0).atTime(time)
                val offsets = zoneId.rules.getValidOffsets(local)
                if (offsets.size != 1) return invalid(CompanionDropoffField.ARRIVAL, "시계가 바뀌는 시각이에요. 다른 시간을 선택해주세요")
                runCatching { local.toInstant(offsets.single()).toEpochMilli() }.getOrNull()
                    ?: return invalid(CompanionDropoffField.CLOCK, "도착 시각을 계산할 수 없어요. 기기 시각을 확인해주세요")
            }
        }
        if (at != null && (at < nowMillis || at - nowMillis > 48 * 60 * 60 * 1000L)) {
            return invalid(CompanionDropoffField.ARRIVAL, "도착 시간은 현재 이후 48시간 안으로 선택해주세요")
        }
        if ((issuedId == null && issuedRevision != 0) ||
            (issuedId != null && (!issuedId.matches(Regex("[A-Za-z0-9_-]{8,64}")) || issuedRevision !in 1 until 100_000))) {
            return invalid(CompanionDropoffField.CLOCK, "제안의 수정 정보를 확인하고 새 하차지를 작성해주세요")
        }
        val proposal = runCatching {
            DropoffProposal(issuedId ?: UUID.randomUUID().toString(), issuedRevision + 1, nowMillis, nowMillis + JourneyProposal.MAX_LIFETIME_MILLIS,
                place, name, at)
        }.getOrNull() ?: return invalid(CompanionDropoffField.PLACE, "하차할 주소와 좌표를 다시 선택해주세요")
        return CompanionDropoffResult.Created(proposal)
    }

    fun withIssuedProposal(proposal: DropoffProposal): CompanionDropoffDraft {
        require(proposal.place == place)
        require(issuedId == null || (issuedId == proposal.id && proposal.revision >= issuedRevision))
        return copy(issuedId = proposal.id, issuedRevision = proposal.revision)
    }

    override fun toString(): String = "CompanionDropoffDraft(conditions=redacted)"

    companion object {
        fun forAddress(place: NavigationPoint, nowMillis: Long, zoneId: ZoneId) = CompanionDropoffDraft(
            place, nowMillis, Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate(), zoneId,
        )

        fun from(proposal: DropoffProposal, nowMillis: Long, zoneId: ZoneId) =
            forAddress(proposal.place, nowMillis, zoneId).copy(passengerName = proposal.passengerName,
                deadline = CompanionDeadlineChoice.KEEP_ORIGINAL, source = proposal)

        private fun invalid(field: CompanionDropoffField, reason: String) = CompanionDropoffResult.Invalid(mapOf(field to reason))
    }
}

internal enum class CompanionDropoffField { NAME, ARRIVAL, CLOCK, PLACE }

internal sealed interface CompanionDropoffResult {
    data class Created(val proposal: DropoffProposal) : CompanionDropoffResult
    data class Invalid(val errors: Map<CompanionDropoffField, String>) : CompanionDropoffResult
}
