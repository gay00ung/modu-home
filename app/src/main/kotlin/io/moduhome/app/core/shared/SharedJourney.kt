package io.moduhome.app.core.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.ErrandVisit
import io.moduhome.app.core.planning.TimeBudget

internal enum class TravelEstimateKind { USER_INPUT, ROUTING_SERVICE, SAMPLE }

/** A route response is time-stamped; it does not imply that live traffic was included. */
internal data class TravelEvidence(
    val kind: TravelEstimateKind,
    val source: String,
    val observedAtMillis: Long? = null,
    val includesLiveTraffic: Boolean = false,
) {
    init {
        require(source.isNotBlank())
        require(observedAtMillis == null || observedAtMillis >= 0)
        require(kind != TravelEstimateKind.ROUTING_SERVICE || observedAtMillis != null)
        require(!includesLiveTraffic || kind == TravelEstimateKind.ROUTING_SERVICE)
    }
}

internal data class SharedJourneyPlace(val id: String, val point: NavigationPoint) {
    init { require(id.isNotBlank()) }
}

internal data class DrivingLegKey(val fromPlaceId: String, val toPlaceId: String) {
    init { require(fromPlaceId.isNotBlank() && toPlaceId.isNotBlank()) }
}

internal data class SharedDrivingEstimate(val minutes: Int, val evidence: TravelEvidence) {
    init { require(minutes in 0..480) }
}

internal enum class AccessCheck { NOT_CHECKED, SUITABLE, UNSUITABLE }

/** Both the vehicle approach and the onward pedestrian connection need their own check. */
internal data class DropoffVerification(
    val vehicleAccess: AccessCheck = AccessCheck.NOT_CHECKED,
    val pedestrianConnection: AccessCheck = AccessCheck.NOT_CHECKED,
    val source: String = "",
    val checkedAtMillis: Long? = null,
) {
    init {
        val hasCheck = vehicleAccess != AccessCheck.NOT_CHECKED || pedestrianConnection != AccessCheck.NOT_CHECKED
        require(!hasCheck || (source.isNotBlank() && checkedAtMillis != null))
        require(checkedAtMillis == null || checkedAtMillis >= 0)
    }
}

internal enum class OnwardTravelMode { WALK, TRANSIT }

/** Total minutes include the walking component and any waiting included by the source. */
internal data class OnwardJourneyEstimate(
    val mode: OnwardTravelMode,
    val totalMinutes: Int,
    val walkingMinutes: Int,
    val transfers: Int = 0,
    val evidence: TravelEvidence,
) {
    init {
        require(totalMinutes in 0..480)
        require(walkingMinutes in 0..totalMinutes)
        require(transfers in 0..10)
        require(mode != OnwardTravelMode.WALK || (walkingMinutes == totalMinutes && transfers == 0))
    }
}

internal data class SharedDropoffCandidate(
    val id: String,
    val place: SharedJourneyPlace,
    val onward: OnwardJourneyEstimate,
    val verification: DropoffVerification = DropoffVerification(),
    val stopMinutes: Int = 1,
) {
    init {
        require(id.isNotBlank())
        require(stopMinutes in 0..30)
    }
}

internal data class SharedPassenger(
    val id: String,
    val name: String,
    val candidates: List<SharedDropoffCandidate>,
    val latestArrivalMinute: Int? = null,
    val maximumOnboardMinutes: Int? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(candidates.size in 1..10)
        require(candidates.map { it.id }.distinct().size == candidates.size)
        require(latestArrivalMinute == null || latestArrivalMinute in 0..2879)
        require(maximumOnboardMinutes == null || maximumOnboardMinutes in 0..480)
    }
}

internal data class SharedErrand(val place: SharedJourneyPlace, val visit: ErrandVisit)

internal data class SharedJourneyRequest(
    val origin: SharedJourneyPlace,
    val home: SharedJourneyPlace,
    val departureMinute: Int,
    val allowedDriverAdditionalMinutes: Int,
    val passengers: List<SharedPassenger>,
    val drivingEstimates: Map<DrivingLegKey, SharedDrivingEstimate>,
    val errand: SharedErrand? = null,
) {
    init {
        require(departureMinute in 0..1439)
        require(allowedDriverAdditionalMinutes in 0..240)
        require(passengers.size in 1..2)
        require(passengers.map { it.id }.distinct().size == passengers.size)
        val places = listOf(origin, home) + passengers.flatMap { passenger -> passenger.candidates.map { it.place } } +
            listOfNotNull(errand?.place)
        require(places.groupBy { it.id }.values.all { group -> group.map { it.point }.distinct().size == 1 })
    }
}

internal sealed interface SharedScheduledStop {
    val place: SharedJourneyPlace
    val arrivalMinute: Int?
    val departureMinute: Int?

    data class Dropoff(
        val passenger: SharedPassenger,
        val candidate: SharedDropoffCandidate,
        override val arrivalMinute: Int?,
        override val departureMinute: Int?,
    ) : SharedScheduledStop {
        override val place: SharedJourneyPlace get() = candidate.place
    }

    data class Errand(
        val errand: SharedErrand,
        override val arrivalMinute: Int?,
        val serviceFinishedMinute: Int?,
        override val departureMinute: Int?,
    ) : SharedScheduledStop {
        override val place: SharedJourneyPlace get() = errand.place
    }
}

internal data class SharedPassengerArrival(
    val passengerId: String,
    val passengerName: String,
    val candidate: SharedDropoffCandidate,
    val dropoffMinute: Int?,
    val homeArrivalMinute: Int?,
    val onboardMinutes: Int?,
)

internal sealed interface SharedPlanIssue {
    data class MissingDrivingEstimate(val leg: DrivingLegKey) : SharedPlanIssue
    data class DriverOverBudget(val excessMinutes: Int) : SharedPlanIssue
    data class PassengerLate(val passengerId: String, val lateMinutes: Int) : SharedPlanIssue
    data class PassengerRideTooLong(val passengerId: String, val excessMinutes: Int) : SharedPlanIssue
    data class UnverifiedDropoff(val passengerId: String, val candidateId: String) : SharedPlanIssue
    data class UnsuitableDropoff(val passengerId: String, val candidateId: String) : SharedPlanIssue
    data object ErrandNotReady : SharedPlanIssue
    data class ErrandDeadline(val lateMinutes: Int) : SharedPlanIssue
}

internal data class SharedJourneyPlan(
    val id: String,
    val stops: List<SharedScheduledStop>,
    val passengerArrivals: List<SharedPassengerArrival>,
    val driverHomeArrivalMinute: Int?,
    val drivingMinutes: Int?,
    val stopMinutes: Int,
    val driverAdditionalMinutes: Int?,
    val totalPersonMinutes: Int?,
    val evidence: List<TravelEvidence>,
    val issues: List<SharedPlanIssue>,
) {
    val isValid: Boolean get() = issues.isEmpty()
    val includesSampleTimes: Boolean get() = evidence.any { it.kind == TravelEstimateKind.SAMPLE }
    val includesUserEstimates: Boolean get() = evidence.any { it.kind == TravelEstimateKind.USER_INPUT }
}

/** Enumerates candidate combinations and every stop order; the driver's home is always last. */
internal class SharedJourneyPlanner {
    /** Route loaders use these identities and endpoint chains before collecting any responses. */
    fun orders(request: SharedJourneyRequest): List<SharedJourneyOrder> =
        plannedOrders(request).map { order -> describeOrder(request, order) }

    fun compare(request: SharedJourneyRequest): List<SharedJourneyPlan> =
        sorted(plannedOrders(request).map { order -> schedule(request, order) })

    fun compare(request: SharedJourneyRequest, travel: SharedOrderTravelTimes): List<SharedJourneyPlan> {
        require(travel.matches(request)) { "장소나 방문 순서가 바뀌었어요. 모든 경로를 다시 조회해주세요" }
        return sorted(plannedOrders(request).map { order ->
            schedule(request, order, travel.directHome, travel.legsFor(describeOrder(request, order)))
        })
    }

    private fun plannedOrders(request: SharedJourneyRequest): List<List<PlannedStop>> {
        val combinations = candidateCombinations(request.passengers)
        return combinations.flatMap { dropoffs ->
            val stops = dropoffs + listOfNotNull(request.errand?.let { PlannedStop.Errand(it) })
            permutations(stops)
        }
    }

    private fun describeOrder(request: SharedJourneyRequest, order: List<PlannedStop>): SharedJourneyOrder =
        SharedJourneyOrder(order.map { it.id }, listOf(request.origin) + order.map { it.place } + request.home)

    private fun sorted(plans: List<SharedJourneyPlan>): List<SharedJourneyPlan> = plans.sortedWith(
        compareBy<SharedJourneyPlan> { !it.isValid }
            .thenBy { it.totalPersonMinutes ?: Int.MAX_VALUE }
            .thenBy { it.driverAdditionalMinutes ?: Int.MAX_VALUE }
            .thenBy { it.id },
    )

    private fun candidateCombinations(passengers: List<SharedPassenger>): List<List<PlannedStop>> =
        passengers.fold(listOf(emptyList<PlannedStop>())) { combinations, passenger ->
            combinations.flatMap { combination ->
                passenger.candidates.map { candidate -> combination + PlannedStop.Dropoff(passenger, candidate) }
            }
        }

    private fun <T> permutations(values: List<T>): List<List<T>> {
        if (values.isEmpty()) return listOf(emptyList())
        return values.flatMapIndexed { index, value ->
            val remainder = values.filterIndexed { other, _ -> other != index }
            permutations(remainder).map { listOf(value) + it }
        }
    }

    private fun schedule(
        request: SharedJourneyRequest,
        order: List<PlannedStop>,
        orderedDirectHome: SharedOrderedDrivingLeg? = null,
        orderedLegs: List<SharedOrderedDrivingLeg>? = null,
    ): SharedJourneyPlan {
        val issues = linkedSetOf<SharedPlanIssue>()
        val evidence = linkedSetOf<TravelEvidence>()
        fun legMinutes(from: SharedJourneyPlace, to: SharedJourneyPlace, index: Int = 0): Int? {
            if (orderedLegs != null) {
                val estimate = orderedLegs[index].estimate
                evidence += estimate.evidence
                return estimate.minutes
            }
            if (from.id == to.id) return 0
            val key = DrivingLegKey(from.id, to.id)
            val estimate = request.drivingEstimates[key]
            if (estimate == null) {
                issues += SharedPlanIssue.MissingDrivingEstimate(key)
                return null
            }
            evidence += estimate.evidence
            return estimate.minutes
        }
        val directHomeMinutes = if (orderedLegs != null) {
            val estimate = requireNotNull(orderedDirectHome).estimate
            evidence += estimate.evidence
            estimate.minutes
        } else legMinutes(request.origin, request.home)
        var previous = request.origin
        var minute: Int? = request.departureMinute
        var driving: Int? = 0
        var stopMinutes = 0
        val arrivals = mutableMapOf<String, SharedPassengerArrival>()
        val scheduled = order.mapIndexed { index, stop ->
            val leg = legMinutes(previous, stop.place, index)
            driving = sumIfKnown(driving, leg)
            val arrival = sumIfKnown(minute, leg)
            val scheduledStop = when (stop) {
                is PlannedStop.Dropoff -> {
                    val passenger = stop.passenger
                    val candidate = stop.candidate
                    val verification = candidate.verification
                    when {
                        verification.vehicleAccess == AccessCheck.UNSUITABLE ||
                            verification.pedestrianConnection == AccessCheck.UNSUITABLE ->
                            issues += SharedPlanIssue.UnsuitableDropoff(passenger.id, candidate.id)
                        verification.vehicleAccess == AccessCheck.NOT_CHECKED ||
                            verification.pedestrianConnection == AccessCheck.NOT_CHECKED ->
                            issues += SharedPlanIssue.UnverifiedDropoff(passenger.id, candidate.id)
                    }
                    stopMinutes += candidate.stopMinutes
                    minute = arrival?.plus(candidate.stopMinutes)
                    val dropoffMinute = minute
                    val homeArrival = dropoffMinute?.plus(candidate.onward.totalMinutes)
                    val onboardMinutes = dropoffMinute?.minus(request.departureMinute)
                    val deadline = passenger.latestArrivalMinute
                    if (deadline != null && homeArrival != null && homeArrival > deadline) {
                        issues += SharedPlanIssue.PassengerLate(passenger.id, homeArrival - deadline)
                    }
                    val onboardLimit = passenger.maximumOnboardMinutes
                    if (onboardLimit != null && onboardMinutes != null && onboardMinutes > onboardLimit) {
                        issues += SharedPlanIssue.PassengerRideTooLong(passenger.id, onboardMinutes - onboardLimit)
                    }
                    evidence += candidate.onward.evidence
                    arrivals[passenger.id] = SharedPassengerArrival(
                        passenger.id, passenger.name, candidate, dropoffMinute, homeArrival, onboardMinutes,
                    )
                    SharedScheduledStop.Dropoff(passenger, candidate, arrival, dropoffMinute)
                }
                is PlannedStop.Errand -> {
                    val visit = stop.errand.visit
                    val serviceFinished = arrival?.plus(visit.walkingMinutesOneWay + visit.stayMinutes)
                    minute = serviceFinished?.plus(visit.walkingMinutesOneWay)
                    stopMinutes += visit.walkingMinutesOneWay * 2 + visit.stayMinutes
                    if (!visit.ready) issues += SharedPlanIssue.ErrandNotReady
                    val deadline = visit.deadlineMinute
                    if (deadline != null && serviceFinished != null && serviceFinished > deadline) {
                        issues += SharedPlanIssue.ErrandDeadline(serviceFinished - deadline)
                    }
                    SharedScheduledStop.Errand(stop.errand, arrival, serviceFinished, minute)
                }
            }
            previous = stop.place
            scheduledStop
        }
        val homeLeg = legMinutes(previous, request.home, order.size)
        driving = sumIfKnown(driving, homeLeg)
        val homeArrival = sumIfKnown(minute, homeLeg)
        val knownDriving = driving
        val budget = if (directHomeMinutes != null && knownDriving != null) {
            TimeBudget(directHomeMinutes, request.allowedDriverAdditionalMinutes).evaluate(knownDriving, stopMinutes)
        } else null
        if (budget != null && !budget.isWithinBudget) {
            issues += SharedPlanIssue.DriverOverBudget(budget.additionalMinutes - request.allowedDriverAdditionalMinutes)
        }
        val passengerArrivals = request.passengers.map { arrivals.getValue(it.id) }
        val totalPersonMinutes = homeArrival?.let { driverArrival ->
            val passengerDurations = passengerArrivals.map { it.homeArrivalMinute?.minus(request.departureMinute) }
            if (passengerDurations.all { it != null }) {
                driverArrival - request.departureMinute + passengerDurations.filterNotNull().sum()
            } else null
        }
        return SharedJourneyPlan(
            id = order.joinToString(">") { it.id },
            stops = scheduled,
            passengerArrivals = passengerArrivals,
            driverHomeArrivalMinute = homeArrival,
            drivingMinutes = driving,
            stopMinutes = stopMinutes,
            driverAdditionalMinutes = budget?.additionalMinutes,
            totalPersonMinutes = totalPersonMinutes,
            evidence = evidence.toList(),
            issues = issues.toList(),
        )
    }

    private fun sumIfKnown(first: Int?, second: Int?): Int? =
        if (first == null || second == null) null else first + second

    private sealed interface PlannedStop {
        val place: SharedJourneyPlace
        val id: String

        data class Dropoff(val passenger: SharedPassenger, val candidate: SharedDropoffCandidate) : PlannedStop {
            override val place: SharedJourneyPlace get() = candidate.place
            override val id: String get() = "P${passenger.id.length}:${passenger.id}${candidate.id.length}:${candidate.id}"
        }

        data class Errand(val errand: SharedErrand) : PlannedStop {
            override val place: SharedJourneyPlace get() = errand.place
            override val id: String get() = "E${errand.place.id.length}:${errand.place.id}"
        }
    }
}
