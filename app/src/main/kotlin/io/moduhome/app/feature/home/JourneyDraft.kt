package io.moduhome.app.feature.home

import io.moduhome.app.core.planning.ErrandJourneyPlanner
import io.moduhome.app.core.planning.ErrandJourneyPlan
import io.moduhome.app.core.planning.ErrandJourneyRequest
import io.moduhome.app.core.planning.ErrandVisit
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.TravelEstimates
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint

internal enum class NavigationPlaceRole(val label: String) {
    HOME("집"), PICKUP("픽업"), GROCERIES("장보기"),
}

internal data class NavigationPlaces(
    val home: NavigationPoint? = null,
    val pickup: NavigationPoint? = null,
    val groceries: NavigationPoint? = null,
)

internal data class VisitDraft(
    val enabled: Boolean = true,
    val place: String,
    val stay: String,
    val walking: String = "0",
    val ready: Boolean = false,
    val deadline: String = "",
    val deadlineNextDay: Boolean = false,
) {
    fun changePlace(value: String): VisitDraft =
        if (value.trim() == place.trim()) copy(place = value) else copy(place = value, ready = false)
}

internal enum class TravelLeg(val label: String, val initial: String) {
    DIRECT("출발지 → 집", "40"),
    ORIGIN_PICKUP("출발지 → 픽업", "12"),
    ORIGIN_GROCERIES("출발지 → 장보기", "24"),
    PICKUP_GROCERIES("픽업 → 장보기", "14"),
    GROCERIES_PICKUP("장보기 → 픽업", "14"),
    PICKUP_HOME("픽업 → 집", "30"),
    GROCERIES_HOME("장보기 → 집", "17"),
}

internal data class JourneyDraft(
    val origin: String = "회사",
    val home: String = "집",
    val departure: String = "18:10",
    val dateEpochDay: Long = 0,
    val budget: Int = 30,
    val pickup: VisitDraft = VisitDraft(place = "베이커리", stay = "5"),
    val groceries: VisitDraft = VisitDraft(place = "동네마트", stay = "15"),
    val travel: Map<TravelLeg, String> = TravelLeg.entries.associateWith { it.initial },
    val navigationPlaces: NavigationPlaces = NavigationPlaces(),
) {
    fun navigationPoint(role: NavigationPlaceRole): NavigationPoint? = when (role) {
        NavigationPlaceRole.HOME -> navigationPlaces.home?.takeIf { it.name.trim() == home.trim() }
        NavigationPlaceRole.PICKUP -> navigationPlaces.pickup?.takeIf { it.name.trim() == pickup.place.trim() }
        NavigationPlaceRole.GROCERIES -> navigationPlaces.groceries?.takeIf { it.name.trim() == groceries.place.trim() }
    }

    fun clearUnmatchedPlaces(): JourneyDraft = copy(navigationPlaces = NavigationPlaces(
        navigationPoint(NavigationPlaceRole.HOME),
        navigationPoint(NavigationPlaceRole.PICKUP),
        navigationPoint(NavigationPlaceRole.GROCERIES),
    ))

    fun chooseNavigationPlace(role: NavigationPlaceRole, point: NavigationPoint): JourneyDraft = when (role) {
        NavigationPlaceRole.HOME -> copy(home = point.name, navigationPlaces = navigationPlaces.copy(home = point))
        NavigationPlaceRole.PICKUP -> copy(
            pickup = pickup.changePlace(point.name).copy(ready = false),
            navigationPlaces = navigationPlaces.copy(pickup = point),
        )
        NavigationPlaceRole.GROCERIES -> copy(
            groceries = groceries.changePlace(point.name),
            navigationPlaces = navigationPlaces.copy(groceries = point),
        )
    }

    fun navigationPlan(plan: ErrandJourneyPlan): NavigationPlan? {
        val destination = navigationPoint(NavigationPlaceRole.HOME) ?: return null
        val waypoints = plan.visits.map { scheduled ->
            navigationPoint(when (scheduled.visit.kind) {
                VisitKind.PICKUP -> NavigationPlaceRole.PICKUP
                VisitKind.GROCERIES -> NavigationPlaceRole.GROCERIES
            }) ?: return null
        }
        return NavigationPlan(destination, waypoints)
    }

    fun newJourney(dateEpochDay: Long, departure: String): JourneyDraft =
        copy(dateEpochDay = dateEpochDay, departure = departure, pickup = pickup.copy(ready = false))

    fun validate(): DraftValidation {
        val errors = mutableMapOf<String, String>()
        fun place(key: String, text: String) {
            if (text.isBlank()) errors[key] = "장소명 또는 주소를 입력해주세요"
        }
        fun number(key: String, text: String, maximum: Int): Int {
            val value = text.toIntOrNull()
            if (value == null || value !in 0..maximum) errors[key] = "0~${maximum}분으로 입력해주세요"
            return value?.coerceIn(0, maximum) ?: 0
        }
        place("origin", origin)
        place("home", home)
        val departureMinute = parseClockMinute(departure)
        if (departureMinute == null) errors["departure"] = "시각을 18:10 형식으로 입력해주세요"
        val visits = mutableListOf<ErrandVisit>()
        fun visit(kind: VisitKind, key: String, draft: VisitDraft) {
            if (!draft.enabled) return
            place("$key.place", draft.place)
            val stay = number("$key.stay", draft.stay, 180)
            val walking = number("$key.walking", draft.walking, 30)
            val clock = if (draft.deadline.isBlank()) null else parseClockMinute(draft.deadline)
            if (draft.deadline.isNotBlank() && clock == null) errors["$key.deadline"] = "시각을 18:10 형식으로 입력해주세요"
            if (draft.place.isNotBlank()) visits += ErrandVisit(
                kind, draft.place.trim(), stay, walking,
                ready = kind != VisitKind.PICKUP || draft.ready,
                deadlineMinute = clock?.plus(if (draft.deadlineNextDay) 1440 else 0),
            )
        }
        visit(VisitKind.PICKUP, "pickup", pickup)
        visit(VisitKind.GROCERIES, "groceries", groceries)
        if (!pickup.enabled && !groceries.enabled) errors["visits"] = "픽업이나 장보기를 한 건 이상 선택해주세요"
        val neededLegs = buildSet {
            add(TravelLeg.DIRECT)
            if (pickup.enabled) { add(TravelLeg.ORIGIN_PICKUP); add(TravelLeg.PICKUP_HOME) }
            if (groceries.enabled) { add(TravelLeg.ORIGIN_GROCERIES); add(TravelLeg.GROCERIES_HOME) }
            if (pickup.enabled && groceries.enabled) { add(TravelLeg.PICKUP_GROCERIES); add(TravelLeg.GROCERIES_PICKUP) }
        }
        val times = neededLegs.associateWith { number("travel.${it.name}", travel[it].orEmpty(), 240) }
        if (budget !in 0..240) errors["budget"] = "추가시간을 0~240분으로 설정해주세요"
        val request = if (errors.isEmpty() && departureMinute != null) {
            ErrandJourneyRequest(origin.trim(), home.trim(), departureMinute, budget, visits,
                TravelEstimates(
                    directHome = times.getValue(TravelLeg.DIRECT),
                    originToPickup = times[TravelLeg.ORIGIN_PICKUP] ?: 0,
                    originToGroceries = times[TravelLeg.ORIGIN_GROCERIES] ?: 0,
                    pickupToGroceries = times[TravelLeg.PICKUP_GROCERIES] ?: 0,
                    groceriesToPickup = times[TravelLeg.GROCERIES_PICKUP] ?: 0,
                    pickupToHome = times[TravelLeg.PICKUP_HOME] ?: 0,
                    groceriesToHome = times[TravelLeg.GROCERIES_HOME] ?: 0,
                ))
        } else null
        return DraftValidation(request, errors)
    }
}

internal data class DraftValidation(val request: ErrandJourneyRequest?, val errors: Map<String, String>)

internal data class HomeUiState(
    val draft: JourneyDraft,
    val progress: JourneyProgress = JourneyProgress(),
    val showErrors: Boolean = false,
) {
    val validation: DraftValidation get() = draft.validate()
    val plans get() = validation.request?.let { ErrandJourneyPlanner().compare(it) }.orEmpty()
    val selectedPlan get() = progress.selected(plans)
}

internal fun parseClockMinute(text: String): Int? {
    if (!text.matches(Regex("\\d{2}:\\d{2}"))) return null
    val hour = text.substring(0, 2).toIntOrNull() ?: return null
    val minute = text.substring(3, 5).toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}
