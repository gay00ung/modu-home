package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

internal sealed interface CompanionErrandSource {
    data class Received(val proposal: ErrandProposal) : CompanionErrandSource {
        override fun toString(): String = "Received(kind=${proposal.kind}, proposal=redacted)"
    }

    data class Address(val place: NavigationPoint, val kind: VisitKind) : CompanionErrandSource {
        override fun toString(): String = "Address(kind=$kind, place=redacted)"
    }
}

/** Editable phone input. Creating a local draft does not insert a received proposal. */
internal data class CompanionErrandDraft(
    val source: CompanionErrandSource,
    val baseDate: LocalDate,
    val zoneId: ZoneId,
    private val openedAtMillis: Long,
    val stayMinutes: String,
    val walkingMinutesOneWay: String,
    val ready: Boolean,
    val deadline: CompanionDeadlineChoice = CompanionDeadlineChoice.KEEP_ORIGINAL,
    val deadlineTime: String = "",
) {
    val place: NavigationPoint get() = when (val origin = source) {
        is CompanionErrandSource.Received -> origin.proposal.place
        is CompanionErrandSource.Address -> origin.place
    }

    val kind: VisitKind get() = when (val origin = source) {
        is CompanionErrandSource.Received -> origin.proposal.kind
        is CompanionErrandSource.Address -> origin.kind
    }

    val originalDeadlineAtMillis: Long? get() = when (val origin = source) {
        is CompanionErrandSource.Received -> origin.proposal.deadlineAtMillis
        is CompanionErrandSource.Address -> null
    }

    /**
     * TODAY/TOMORROW refer to the fixed [baseDate], including after midnight.
     * Keeping the original deadline preserves its exact instant, not a rounded clock time.
     * This only creates a suggestion; it neither delivers nor approves it.
     */
    fun createProposal(nowMillis: Long, zoneId: ZoneId): CompanionErrandResult {
        if (openedAtMillis < 0 || nowMillis < openedAtMillis ||
            nowMillis > Long.MAX_VALUE - ErrandProposal.MAX_LIFETIME_MILLIS ||
            zoneId != this.zoneId ||
            baseDate != Instant.ofEpochMilli(openedAtMillis).atZone(this.zoneId).toLocalDate()
        ) {
            return CompanionErrandResult.Invalid(mapOf(
                CompanionErrandField.CLOCK to "기기 시각이나 시간대가 바뀌었어요. 볼일을 다시 열어주세요",
            ))
        }
        val sourceReason = when (val origin = source) {
            is CompanionErrandSource.Received -> origin.proposal.unavailableReason(nowMillis)
            is CompanionErrandSource.Address -> if (nowMillis - openedAtMillis >= ErrandProposal.MAX_LIFETIME_MILLIS) {
                "작성한 볼일의 유효시간이 지났어요. 장소와 조건을 다시 확인해 새로 작성해주세요"
            } else null
        }
        sourceReason?.let { reason ->
            return CompanionErrandResult.Invalid(mapOf(CompanionErrandField.SOURCE to reason))
        }

        val stay = parseMinutes(stayMinutes, 180)
        val walk = parseMinutes(walkingMinutesOneWay, 30)
        val resolvedDeadline = resolveDeadline(nowMillis)
        val errors = buildMap {
            if (stay == null) put(CompanionErrandField.STAY, "체류 시간을 0~180분으로 입력해주세요")
            if (walk == null) put(CompanionErrandField.WALKING, "편도 도보 시간을 0~30분으로 입력해주세요")
            if (resolvedDeadline is DeadlineResolution.Invalid) {
                put(resolvedDeadline.field, resolvedDeadline.reason)
            }
        }
        if (stay == null || walk == null || resolvedDeadline !is DeadlineResolution.Value) {
            return CompanionErrandResult.Invalid(errors)
        }
        // The canonical proposal validates place text and coordinates too, including
        // local addresses that have not previously passed through a received proposal.
        val proposal = try {
            ErrandProposal(
                id = UUID.randomUUID().toString(),
                revision = 1,
                createdAtMillis = nowMillis,
                expiresAtMillis = nowMillis + ErrandProposal.MAX_LIFETIME_MILLIS,
                kind = kind,
                place = place,
                stayMinutes = stay,
                walkingMinutesOneWay = walk,
                ready = ready,
                deadlineAtMillis = resolvedDeadline.atMillis,
            )
        } catch (_: IllegalArgumentException) {
            return CompanionErrandResult.Invalid(mapOf(
                CompanionErrandField.PLACE to "장소의 이름·주소·좌표를 확인한 뒤 다시 선택해주세요",
            ))
        }
        return CompanionErrandResult.Created(proposal)
    }

    private fun resolveDeadline(nowMillis: Long): DeadlineResolution {
        val atMillis = when (deadline) {
            CompanionDeadlineChoice.KEEP_ORIGINAL -> originalDeadlineAtMillis
            CompanionDeadlineChoice.NONE -> null
            CompanionDeadlineChoice.TODAY, CompanionDeadlineChoice.TOMORROW -> {
                val time = parseTime(deadlineTime) ?: return DeadlineResolution.Invalid(
                    CompanionErrandField.DEADLINE, "마감 시각을 18:10 형식으로 입력해주세요",
                )
                try {
                    val date = baseDate.plusDays(if (deadline == CompanionDeadlineChoice.TOMORROW) 1 else 0)
                    val local = date.atTime(time)
                    val offsets = zoneId.rules.getValidOffsets(local)
                    if (offsets.isEmpty()) return DeadlineResolution.Invalid(
                        CompanionErrandField.DEADLINE, "시계가 바뀌어 존재하지 않는 시각이에요. 다른 마감을 선택해주세요",
                    )
                    if (offsets.size != 1) return DeadlineResolution.Invalid(
                        CompanionErrandField.DEADLINE, "시계가 바뀌어 두 번 나타나는 시각이에요. 다른 마감을 선택해주세요",
                    )
                    local.toInstant(offsets.single()).toEpochMilli()
                } catch (_: DateTimeException) {
                    return DeadlineResolution.Invalid(
                        CompanionErrandField.CLOCK, "기기 시각과 시간대를 확인한 뒤 볼일을 다시 열어주세요",
                    )
                } catch (_: ArithmeticException) {
                    return DeadlineResolution.Invalid(
                        CompanionErrandField.CLOCK, "마감 시각을 계산할 수 없어요. 기기 시각을 확인해주세요",
                    )
                }
            }
        }
        if (atMillis != null && atMillis < nowMillis) return DeadlineResolution.Invalid(
            CompanionErrandField.DEADLINE, "선택한 마감 시각이 지났어요. 날짜와 시각을 다시 선택해주세요",
        )
        if (atMillis != null && atMillis - nowMillis > MAX_DEADLINE_MILLIS) return DeadlineResolution.Invalid(
            CompanionErrandField.DEADLINE, "마감은 발행 시각부터 48시간 이내로 선택해주세요",
        )
        return DeadlineResolution.Value(atMillis)
    }

    override fun toString(): String = "CompanionErrandDraft(kind=$kind, conditions=redacted)"

    companion object {
        private const val MAX_DEADLINE_MILLIS = 48 * 60 * 60 * 1000L

        /** [nowMillis] and [zoneId] are captured once when opening the phone editor. */
        fun from(proposal: ErrandProposal, nowMillis: Long, zoneId: ZoneId): CompanionErrandDraft =
            CompanionErrandDraft(
                source = CompanionErrandSource.Received(proposal),
                baseDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate(),
                zoneId = zoneId,
                openedAtMillis = nowMillis,
                stayMinutes = proposal.stayMinutes.toString(),
                walkingMinutesOneWay = proposal.walkingMinutesOneWay.toString(),
                ready = proposal.ready,
            )

        /** A local address has no inbox identity; only successful creation issues one. */
        fun forAddress(place: NavigationPoint, kind: VisitKind, nowMillis: Long, zoneId: ZoneId): CompanionErrandDraft =
            CompanionErrandDraft(
                source = CompanionErrandSource.Address(place, kind),
                baseDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate(),
                zoneId = zoneId,
                openedAtMillis = nowMillis,
                stayMinutes = when (kind) {
                    VisitKind.PICKUP -> "10"
                    VisitKind.GROCERIES -> "20"
                },
                walkingMinutesOneWay = "2",
                ready = false,
                deadline = CompanionDeadlineChoice.NONE,
            )

        private fun parseMinutes(text: String, maximum: Int): Int? =
            text.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
                ?.toIntOrNull()?.takeIf { it in 0..maximum }

        private fun parseTime(text: String): LocalTime? {
            if (!text.matches(Regex("[0-9]{2}:[0-9]{2}"))) return null
            val hour = text.substring(0, 2).toInt()
            val minute = text.substring(3, 5).toInt()
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
    }
}

internal enum class CompanionDeadlineChoice { KEEP_ORIGINAL, NONE, TODAY, TOMORROW }

internal enum class CompanionErrandField { SOURCE, PLACE, STAY, WALKING, DEADLINE, CLOCK }

internal sealed interface CompanionErrandResult {
    data class Created(val proposal: ErrandProposal) : CompanionErrandResult
    data class Invalid(val errors: Map<CompanionErrandField, String>) : CompanionErrandResult
}

private sealed interface DeadlineResolution {
    data class Value(val atMillis: Long?) : DeadlineResolution
    data class Invalid(val field: CompanionErrandField, val reason: String) : DeadlineResolution
}
