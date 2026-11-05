package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.ErrandVisit
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.SharedDropoffCandidate
import io.moduhome.app.core.shared.SharedErrand
import io.moduhome.app.core.shared.SharedJourneyPlace
import io.moduhome.app.core.shared.SharedJourneyRequest
import io.moduhome.app.core.shared.SharedPassenger
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A passenger chooses the vehicle's dropoff, independently of their home address. */
internal data class SharedPassengerDraft(
    val id: String,
    val name: String,
    val dropoff: NavigationPoint? = null,
    val verification: DropoffVerification = DropoffVerification(),
    val stopMinutes: Int = 1,
    val latestArrivalAtMillis: Long? = null,
    val maximumOnboardMinutes: Int? = null,
) {
    fun chooseDropoff(point: NavigationPoint): SharedPassengerDraft = if (point == dropoff) this else copy(
        dropoff = point,
        verification = DropoffVerification(),
    )
}

internal data class SharedErrandDraft(
    val kind: VisitKind,
    val place: NavigationPoint? = null,
    val stayMinutes: Int = 5,
    val walkingMinutesOneWay: Int = 0,
    val ready: Boolean = false,
    val deadlineAtMillis: Long? = null,
)

internal data class SharedJourneyDraft(
    val driverHome: NavigationPoint? = null,
    val passengers: List<SharedPassengerDraft> = listOf(SharedPassengerDraft("passenger-1", "동승자 1")),
    val errand: SharedErrandDraft? = null,
    val allowedAdditionalMinutes: Int = 30,
) {
    fun validate(
        origin: NavigationPoint,
        departureAtMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SharedDraftValidation {
        val errors = linkedMapOf<String, String>()
        if (departureAtMillis < 0) return SharedDraftValidation(null, mapOf("departure" to "출발 시각을 다시 확인해주세요"))
        val departure = Instant.ofEpochMilli(departureAtMillis).atZone(zoneId)
        val departureMinute = departure.hour * 60 + departure.minute
        fun actualPoint(key: String, point: NavigationPoint?, label: String): NavigationPoint? {
            if (point == null || (point.latitude == 0.0 && point.longitude == 0.0)) {
                errors[key] = "$label 장소를 선택해주세요"
                return null
            }
            return point
        }
        fun deadlineMinute(key: String, atMillis: Long?): Int? {
            if (atMillis == null) return null
            val at = Instant.ofEpochMilli(atMillis).atZone(zoneId)
            val days = ChronoUnit.DAYS.between(departure.toLocalDate(), at.toLocalDate())
            if (atMillis < departureAtMillis || days !in 0L..1L) {
                errors[key] = "마감은 출발 이후부터 다음 날까지로 설정해주세요"
                return null
            }
            return (days * 1440 + at.hour * 60 + at.minute).toInt()
        }
        val actualOrigin = actualPoint("origin", origin, "현재 차량 위치")
        val home = actualPoint("driverHome", driverHome, "운전자 집")
        if (passengers.size !in 1..2) errors["passengers"] = "동승자는 한 명 또는 두 명으로 선택해주세요"
        if (passengers.map { it.id.trim() }.distinct().size != passengers.size) {
            errors["passengers"] = "동승자 정보를 구분해서 선택해주세요"
        }
        if (allowedAdditionalMinutes !in 0..240) errors["budget"] = "허용 추가시간은 0~240분으로 선택해주세요"
        val people = passengers.mapIndexedNotNull { index, draft ->
            val key = "passengers.$index"
            if (draft.id.isBlank() || draft.name.isBlank()) errors[key] = "동승자 정보를 확인해주세요"
            val point = actualPoint("$key.dropoff", draft.dropoff, "${draft.name.ifBlank { "동승자" }} 하차")
            if (draft.stopMinutes !in 0..30) errors["$key.stop"] = "하차 정차시간은 0~30분으로 선택해주세요"
            if (draft.maximumOnboardMinutes != null && draft.maximumOnboardMinutes !in 0..480) {
                errors["$key.onboard"] = "탑승시간 한도는 0~480분으로 설정해주세요"
            }
            val verification = draft.verification
            if (verification.vehicleAccess != AccessCheck.SUITABLE) {
                errors["$key.verification"] = "지정한 하차지에 정차해 내릴 수 있는지 확인해주세요"
            } else if (verification.checkedAtMillis?.let { it > departureAtMillis } == true) {
                errors["$key.verification"] = "하차 장소를 확인한 시각을 다시 확인해주세요"
            }
            val deadline = deadlineMinute("$key.deadline", draft.latestArrivalAtMillis)
            if (point == null || errors.keys.any { it == key || it.startsWith("$key.") }) return@mapIndexedNotNull null
            val id = draft.id.trim()
            SharedPassenger(
                id = id,
                name = draft.name.trim(),
                candidates = listOf(SharedDropoffCandidate(
                    // Persisted order and routing identities remain stable across the scope change.
                    id = "home-doorstep",
                    place = SharedJourneyPlace("passenger:${id.length}:$id:home", point),
                    verification = verification,
                    stopMinutes = draft.stopMinutes,
                )),
                latestArrivalMinute = deadline,
                maximumOnboardMinutes = draft.maximumOnboardMinutes,
            )
        }
        val visit = errand?.let { draft ->
            val point = actualPoint("errand.place", draft.place, "볼일")
            if (draft.stayMinutes !in 0..180) errors["errand.stay"] = "체류시간은 0~180분으로 선택해주세요"
            if (draft.walkingMinutesOneWay !in 0..30) errors["errand.walking"] = "편도 도보는 0~30분으로 설정해주세요"
            val deadline = deadlineMinute("errand.deadline", draft.deadlineAtMillis)
            if (point == null || errors.keys.any { it.startsWith("errand.") }) null else SharedErrand(
                SharedJourneyPlace("errand:${draft.kind.name}", point),
                ErrandVisit(draft.kind, point.name, draft.stayMinutes, draft.walkingMinutesOneWay,
                    ready = draft.kind != VisitKind.PICKUP || draft.ready, deadlineMinute = deadline),
            )
        }
        val request = if (errors.isEmpty() && actualOrigin != null && home != null) SharedJourneyRequest(
            origin = SharedJourneyPlace("origin", actualOrigin),
            home = SharedJourneyPlace("driver-home", home),
            departureMinute = departureMinute,
            allowedDriverAdditionalMinutes = allowedAdditionalMinutes,
            passengers = people,
            drivingEstimates = emptyMap(),
            errand = visit,
        ) else null
        return SharedDraftValidation(request, errors.toMap())
    }
}

internal data class SharedDraftValidation(val request: SharedJourneyRequest?, val errors: Map<String, String>)
