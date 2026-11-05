package io.moduhome.app.core.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.ErrandVisit
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.*
import org.junit.Test

class SharedAdditionalTimeSuggestionTest {
    private val planner = SharedJourneyPlanner()
    private val evidence = TravelEvidence(TravelEstimateKind.USER_INPUT, "입력 시간")
    private fun place(id: String) = SharedJourneyPlace(id, NavigationPoint(id, 37.5, 127.0))
    private val origin = place("출발")
    private val home = place("운전자 집")
    private val dropoff = place("동승자 집")
    private val shop = place("픽업")
    private val candidate = SharedDropoffCandidate("집 앞", dropoff,
        DropoffVerification(AccessCheck.SUITABLE, "직접 확인", 1))
    private val passenger = SharedPassenger("p1", "동승자", listOf(candidate), latestArrivalMinute = 1103)
    private val request: SharedJourneyRequest get() {
        val points = listOf(origin, home, dropoff, shop)
        val estimates = points.flatMap { a -> points.filter { it != a }.map { b ->
            DrivingLegKey(a.id, b.id) to SharedDrivingEstimate(if (a == origin && b == home) 20 else 10, evidence)
        } }.toMap()
        return SharedJourneyRequest(origin, home, 1080, 20, listOf(passenger), estimates,
            SharedErrand(shop, ErrandVisit(VisitKind.PICKUP, "픽업", 10)))
    }

    @Test fun budgetIncreasePreservesThePassengerDeadlineAndChoosesAFeasibleOrder() {
        val input = request
        val suggestion = requireNotNull(planner.suggestAdditionalTime(planner.compare(input), 20))
        assertEquals(21, suggestion.requiredMinutes)
        val feasible = planner.compare(input.copy(allowedDriverAdditionalMinutes = 21)).filter { it.isValid }
        assertEquals(1, feasible.size)
        assertTrue(feasible.single().stops.first() is SharedScheduledStop.Dropoff)
    }

    @Test fun deadlineAndOnboardLimitsAreNeverRelaxed() {
        for (person in listOf(passenger.copy(latestArrivalMinute = 1080), passenger.copy(maximumOnboardMinutes = 0))) {
            assertNull(planner.suggestAdditionalTime(planner.compare(request.copy(passengers = listOf(person))), 20))
        }
    }

    @Test fun dropoffSafetyAndPickupReadinessAreNeverAssumed() {
        for (check in listOf(DropoffVerification(),
            DropoffVerification(AccessCheck.UNSUITABLE, "직접 확인", 1))) {
            val person = passenger.copy(candidates = listOf(candidate.copy(verification = check)))
            assertNull(planner.suggestAdditionalTime(planner.compare(request.copy(passengers = listOf(person))), 20))
        }
        val unready = request.copy(errand = requireNotNull(request.errand).let { it.copy(visit = it.visit.copy(ready = false)) })
        assertNull(planner.suggestAdditionalTime(planner.compare(unready), 20))
    }

    @Test fun missingDrivingEvidenceAndErrandDeadlineDoNotBecomeBudgetAdvice() {
        val input = request
        val missing = input.copy(drivingEstimates = input.drivingEstimates - DrivingLegKey(origin.id, home.id))
        assertNull(planner.suggestAdditionalTime(planner.compare(missing), 20))
        val expired = input.copy(errand = requireNotNull(input.errand).let { it.copy(visit = it.visit.copy(deadlineMinute = 1080)) })
        assertNull(planner.suggestAdditionalTime(planner.compare(expired), 20))
    }

    @Test fun oneFeasibleOrderSuppressesAdviceEvenIfOtherOrdersFail() {
        assertNull(planner.suggestAdditionalTime(planner.compare(request.copy(allowedDriverAdditionalMinutes = 21)), 21))
    }
}
