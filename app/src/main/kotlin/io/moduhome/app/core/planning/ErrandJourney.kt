package io.moduhome.app.core.planning

internal enum class VisitKind { PICKUP, GROCERIES }

internal data class ErrandVisit(
    val kind: VisitKind,
    val place: String,
    val stayMinutes: Int,
    val walkingMinutesOneWay: Int = 0,
    val ready: Boolean = true,
    val deadlineMinute: Int? = null,
) {
    init {
        require(place.isNotBlank())
        require(stayMinutes in 0..180)
        require(walkingMinutesOneWay in 0..30)
        require(deadlineMinute == null || deadlineMinute in 0..2879)
    }
}

internal data class TravelEstimates(
    val directHome: Int = 40,
    val originToPickup: Int = 12,
    val originToGroceries: Int = 24,
    val pickupToGroceries: Int = 14,
    val groceriesToPickup: Int = 14,
    val pickupToHome: Int = 30,
    val groceriesToHome: Int = 17,
) {
    init {
        require(listOf(directHome, originToPickup, originToGroceries, pickupToGroceries,
            groceriesToPickup, pickupToHome, groceriesToHome).all { it in 0..240 })
    }

    fun fromOrigin(to: VisitKind): Int = when (to) {
        VisitKind.PICKUP -> originToPickup
        VisitKind.GROCERIES -> originToGroceries
    }

    fun between(from: VisitKind, to: VisitKind): Int {
        require(from != to)
        return when (from) {
            VisitKind.PICKUP -> pickupToGroceries
            VisitKind.GROCERIES -> groceriesToPickup
        }
    }

    fun toHome(from: VisitKind): Int = when (from) {
        VisitKind.PICKUP -> pickupToHome
        VisitKind.GROCERIES -> groceriesToHome
    }
}

internal data class ErrandJourneyRequest(
    val origin: String,
    val home: String,
    val departureMinute: Int,
    val allowedAdditionalMinutes: Int,
    val visits: List<ErrandVisit>,
    val travel: TravelEstimates,
) {
    init {
        require(origin.isNotBlank() && home.isNotBlank())
        require(departureMinute in 0..1439)
        require(allowedAdditionalMinutes in 0..240)
        require(visits.size in 1..2 && visits.map { it.kind }.distinct().size == visits.size)
    }
}

internal data class ScheduledVisit(
    val visit: ErrandVisit,
    val arrivalMinute: Int,
    val serviceFinishedMinute: Int,
    val departureMinute: Int,
) {
    val deadlineSlackMinutes: Int? get() = visit.deadlineMinute?.minus(serviceFinishedMinute)
}

internal enum class PlanIssueKind { OVER_BUDGET, NOT_READY, DEADLINE }
internal data class PlanIssue(val kind: PlanIssueKind, val visit: VisitKind? = null, val minutes: Int = 0)

internal data class ErrandJourneyPlan(
    val id: String,
    val visits: List<ScheduledVisit>,
    val drivingMinutes: Int,
    val walkingMinutes: Int,
    val stayMinutes: Int,
    val homeArrivalMinute: Int,
    val additionalMinutes: Int,
    val issues: List<PlanIssue>,
) {
    val totalMinutes: Int get() = drivingMinutes + walkingMinutes + stayMinutes
    val isValid: Boolean get() = issues.isEmpty()
}

internal class ErrandJourneyPlanner {
    fun suggestAdditionalTime(plans: List<ErrandJourneyPlan>, allowedMinutes: Int): AdditionalTimeSuggestion? {
        if (allowedMinutes !in 0..240 || plans.any { it.isValid }) return null
        return AdditionalTimeSuggestion.fromCandidates(allowedMinutes, plans.filter { plan ->
            plan.issues.isNotEmpty() && plan.issues.all { it.kind == PlanIssueKind.OVER_BUDGET }
        }.map { it.additionalMinutes })
    }

    fun compare(request: ErrandJourneyRequest): List<ErrandJourneyPlan> {
        return compareOrders(request) { order ->
            order.mapIndexed { index, visit ->
                if (index == 0) request.travel.fromOrigin(visit.kind)
                else request.travel.between(order[index - 1].kind, visit.kind)
            } + request.travel.toHome(order.last().kind)
        }
    }

    fun compare(request: ErrandJourneyRequest, routedTimes: RoutedJourneyTimes): List<ErrandJourneyPlan> =
        compareOrders(request, directHomeMinutes = routedTimes.directHomeMinutes) { order ->
            routedTimes.legsFor(order.map { it.kind })
        }

    private fun compareOrders(
        request: ErrandJourneyRequest,
        directHomeMinutes: Int = request.travel.directHome,
        legsFor: (List<ErrandVisit>) -> List<Int>,
    ): List<ErrandJourneyPlan> {
        val orders = if (request.visits.size == 2) listOf(request.visits, request.visits.reversed())
            else listOf(request.visits)
        return orders.map { schedule(request, it, legsFor(it), directHomeMinutes) }
            .sortedWith(compareBy<ErrandJourneyPlan> { !it.isValid }.thenBy { it.totalMinutes }.thenBy { it.id })
    }

    private fun schedule(
        request: ErrandJourneyRequest,
        order: List<ErrandVisit>,
        legs: List<Int>,
        directHomeMinutes: Int,
    ): ErrandJourneyPlan {
        require(legs.size == order.size + 1)
        var minute = request.departureMinute
        var driving = 0
        val issues = mutableListOf<PlanIssue>()
        val scheduled = order.mapIndexed { index, visit ->
            val leg = legs[index]
            driving += leg
            val arrival = minute + leg
            val serviceFinished = arrival + visit.walkingMinutesOneWay + visit.stayMinutes
            minute = serviceFinished + visit.walkingMinutesOneWay
            if (!visit.ready) issues += PlanIssue(PlanIssueKind.NOT_READY, visit.kind)
            val deadline = visit.deadlineMinute
            if (deadline != null && serviceFinished > deadline) {
                issues += PlanIssue(PlanIssueKind.DEADLINE, visit.kind, serviceFinished - deadline)
            }
            ScheduledVisit(visit, arrival, serviceFinished, minute)
        }
        val homeLeg = legs.last()
        driving += homeLeg
        minute += homeLeg
        val walking = order.sumOf { it.walkingMinutesOneWay * 2 }
        val stay = order.sumOf { it.stayMinutes }
        val budget = TimeBudget(directHomeMinutes, request.allowedAdditionalMinutes)
            .evaluate(driving, stay, walking)
        if (!budget.isWithinBudget) {
            issues += PlanIssue(PlanIssueKind.OVER_BUDGET, minutes = budget.additionalMinutes - request.allowedAdditionalMinutes)
        }
        return ErrandJourneyPlan(
            id = order.joinToString(">") { it.kind.name }, visits = scheduled,
            drivingMinutes = driving, walkingMinutes = walking, stayMinutes = stay,
            homeArrivalMinute = minute, additionalMinutes = budget.additionalMinutes, issues = issues,
        )
    }
}

internal enum class JourneyStage { INPUT, COMPARE, REVIEW, ACTIVE, COMPLETE }

internal data class JourneyProgress(
    val stage: JourneyStage = JourneyStage.INPUT,
    val selectedPlanId: String? = null,
    val completedVisits: Int = 0,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
) {
    fun compare(plans: List<ErrandJourneyPlan>): JourneyProgress =
        if (stage == JourneyStage.INPUT && plans.isNotEmpty()) {
            copy(stage = JourneyStage.COMPARE, selectedPlanId = plans.first().id)
        } else this

    fun select(id: String, plans: List<ErrandJourneyPlan>): JourneyProgress =
        if (stage == JourneyStage.COMPARE && plans.any { it.id == id }) copy(selectedPlanId = id) else this

    fun review(plans: List<ErrandJourneyPlan>): JourneyProgress =
        if (stage == JourneyStage.COMPARE && selected(plans)?.isValid == true) copy(stage = JourneyStage.REVIEW) else this

    fun start(plans: List<ErrandJourneyPlan>, nowMillis: Long): JourneyProgress =
        if (stage == JourneyStage.REVIEW && selected(plans)?.isValid == true) {
            copy(stage = JourneyStage.ACTIVE, startedAtMillis = nowMillis, completedVisits = 0, completedAtMillis = null)
        } else this

    fun completeVisit(plans: List<ErrandJourneyPlan>, expectedVisit: VisitKind, nowMillis: Long): JourneyProgress {
        val plan = selected(plans) ?: return this
        val started = startedAtMillis ?: return this
        if (started < 0 || nowMillis < started) return this
        return if (stage == JourneyStage.ACTIVE && plan.visits.getOrNull(completedVisits)?.visit?.kind == expectedVisit) {
            copy(completedVisits = completedVisits + 1)
        } else this
    }

    fun finish(plans: List<ErrandJourneyPlan>, nowMillis: Long): JourneyProgress {
        val plan = selected(plans) ?: return this
        return if (stage == JourneyStage.ACTIVE && completedVisits == plan.visits.size) {
            copy(stage = JourneyStage.COMPLETE, completedAtMillis = nowMillis)
        } else this
    }

    fun back(): JourneyProgress = when (stage) {
        JourneyStage.INPUT, JourneyStage.ACTIVE, JourneyStage.COMPLETE -> this
        JourneyStage.COMPARE -> JourneyProgress()
        JourneyStage.REVIEW -> copy(stage = JourneyStage.COMPARE)
    }

    fun selected(plans: List<ErrandJourneyPlan>): ErrandJourneyPlan? = plans.firstOrNull { it.id == selectedPlanId }
}

internal fun formatJourneyMinute(minute: Int): String {
    require(minute >= 0)
    val day = minute / 1440
    val clock = "%02d:%02d".format(java.util.Locale.ROOT, (minute / 60) % 24, minute % 60)
    return if (day == 0) clock else "$clock (+${day}일)"
}
