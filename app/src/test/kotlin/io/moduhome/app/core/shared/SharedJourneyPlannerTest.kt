package io.moduhome.app.core.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.ErrandVisit
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.*
import org.junit.Test

class SharedJourneyPlannerTest {
    private val entered = TravelEvidence(TravelEstimateKind.USER_INPUT, "사용자 입력")
    private val checked = DropoffVerification(AccessCheck.SUITABLE, "현장 확인", 1L)
    private val origin = place("origin")
    private val home = place("home")
    private val station = place("station")
    private val otherStation = place("other-station")
    private val shop = place("shop")
    private val candidate = SharedDropoffCandidate("station", station, checked)
    private val passenger = SharedPassenger("passenger", "동승자", listOf(candidate))
    private val errand = SharedErrand(shop, ErrandVisit(VisitKind.PICKUP, "픽업", 10))
    private val planner = SharedJourneyPlanner()

    private fun place(id: String) = SharedJourneyPlace(id, NavigationPoint(id, 37.5, 127.0))

    private fun request(
        passengers: List<SharedPassenger> = listOf(passenger),
        errand: SharedErrand? = null,
        budget: Int = 60,
        departure: Int = 1080,
        overrides: Map<DrivingLegKey, SharedDrivingEstimate> = emptyMap(),
    ): SharedJourneyRequest {
        val places = listOf(origin, home) + passengers.flatMap { p -> p.candidates.map { it.place } } + listOfNotNull(errand?.place)
        val legs = places.distinctBy { it.id }.flatMap { from ->
            places.distinctBy { it.id }.filter { it.id != from.id }.map { to ->
                DrivingLegKey(from.id, to.id) to SharedDrivingEstimate(10, entered)
            }
        }.toMap() + (DrivingLegKey(origin.id, home.id) to SharedDrivingEstimate(20, entered)) + overrides
        return SharedJourneyRequest(origin, home, departure, budget, passengers, legs, errand)
    }

    @Test fun passengerArrivalEndsAtTheSelectedDropoffWhileDriverTimeIncludesAlighting() {
        val plan = planner.compare(request()).single()
        assertTrue(plan.isValid)
        assertEquals(1101, plan.driverHomeArrivalMinute)
        assertEquals(1, plan.driverAdditionalMinutes)
        assertEquals(1090, plan.passengerArrivals.single().dropoffMinute)
        assertEquals(10, plan.passengerArrivals.single().onboardMinutes)
        assertEquals(31, plan.totalPersonMinutes)
        assertEquals(1091, plan.stops.single().departureMinute)
        assertTrue(plan.includesUserEstimates)
        assertFalse(plan.includesSampleTimes)
    }

    @Test fun anErrandAfterDropoffDoesNotDelayThePassenger() {
        val plans = planner.compare(request(errand = errand))
        val after = plans.single { it.stops.first() is SharedScheduledStop.Dropoff }
        val before = plans.single { it.stops.first() is SharedScheduledStop.Errand }
        assertEquals(1090, after.passengerArrivals.single().dropoffMinute)
        assertEquals(1110, before.passengerArrivals.single().dropoffMinute)
        assertEquals(1121, after.driverHomeArrivalMinute)
        assertEquals(after.driverHomeArrivalMinute, before.driverHomeArrivalMinute)
        assertTrue(after.totalPersonMinutes!! < before.totalPersonMinutes!!)
        assertEquals(after.id, plans.first().id)
    }

    @Test fun allCandidateCombinationsAndOrdersAreComparedForTwoPassengers() {
        val alternate = candidate.copy(id = "alternate", place = otherStation)
        val first = passenger.copy(candidates = listOf(candidate, alternate))
        val second = SharedPassenger("second", "다른 동승자", listOf(candidate, alternate))
        val plans = planner.compare(request(listOf(first, second), errand))
        assertEquals(24, plans.size)
        assertEquals(24, plans.map { it.id }.distinct().size)
        plans.forEach { plan ->
            assertEquals(3, plan.stops.size)
            assertEquals(listOf("passenger", "second"), plan.passengerArrivals.map { it.passengerId })
            assertEquals(2, plan.stops.filterIsInstance<SharedScheduledStop.Dropoff>().size)
            assertEquals(1, plan.stops.filterIsInstance<SharedScheduledStop.Errand>().size)
        }
    }

    @Test fun aSharedDropoffLocationDoesNotRequireAFictitiousDrivingLeg() {
        val second = passenger.copy(id = "second", name = "두 번째 동승자")
        val plans = planner.compare(request(listOf(passenger, second)))
        assertEquals(2, plans.size)
        assertTrue(plans.all { it.isValid })
        assertTrue(plans.all { it.drivingMinutes == 20 && it.stopMinutes == 2 })
    }

    @Test fun asymmetricDrivingTimesCanChangeTheBestPassengerOrder() {
        val secondCandidate = candidate.copy(id = "other", place = otherStation)
        val second = passenger.copy(id = "second", name = "두 번째", candidates = listOf(secondCandidate))
        val legs = mapOf(
            DrivingLegKey(station.id, otherStation.id) to SharedDrivingEstimate(35, entered),
            DrivingLegKey(otherStation.id, station.id) to SharedDrivingEstimate(3, entered),
        )
        val plans = planner.compare(request(listOf(passenger, second), overrides = legs))
        assertEquals("second", (plans.first().stops.first() as SharedScheduledStop.Dropoff).passenger.id)
        assertEquals(23, plans.first().drivingMinutes)
        assertEquals(55, plans.last().drivingMinutes)
    }

    @Test fun driverBudgetAndPassengerDeadlineIncludeEveryAffectedStop() {
        val boundedPassenger = passenger.copy(latestArrivalMinute = 1090)
        val plans = planner.compare(request(listOf(boundedPassenger), errand, budget = 20))
        assertTrue(plans.all { it.issues.contains(SharedPlanIssue.DriverOverBudget(1)) })
        val after = plans.single { it.stops.first() is SharedScheduledStop.Dropoff }
        assertFalse(after.issues.any { it is SharedPlanIssue.PassengerLate })
        val before = plans.single { it.stops.first() is SharedScheduledStop.Errand }
        assertTrue(before.issues.contains(SharedPlanIssue.PassengerLate(passenger.id, 20)))
        assertTrue(plans.none { it.isValid })
    }

    @Test fun exactBudgetDeadlineAndOnboardLimitAreAllowed() {
        val bounded = passenger.copy(latestArrivalMinute = 1090, maximumOnboardMinutes = 10)
        val plan = planner.compare(request(listOf(bounded), budget = 1)).single()
        assertTrue(plan.isValid)
    }

    @Test fun oneMinuteOverTheOnboardLimitIsExplained() {
        val plan = planner.compare(request(listOf(passenger.copy(maximumOnboardMinutes = 9)))).single()
        assertEquals(listOf(SharedPlanIssue.PassengerRideTooLong(passenger.id, 1)), plan.issues)
    }

    @Test fun aMissingLegNeverBecomesAZeroMinuteRoute() {
        val initial = request()
        val missing = DrivingLegKey(origin.id, station.id)
        val plan = planner.compare(initial.copy(drivingEstimates = initial.drivingEstimates - missing)).single()
        assertFalse(plan.isValid)
        assertTrue(plan.issues.contains(SharedPlanIssue.MissingDrivingEstimate(missing)))
        assertNull(plan.driverHomeArrivalMinute)
        assertNull(plan.drivingMinutes)
        assertNull(plan.driverAdditionalMinutes)
        assertNull(plan.passengerArrivals.single().dropoffMinute)
        assertNull(plan.totalPersonMinutes)
    }

    @Test fun aMissingDirectRouteKeepsKnownArrivalsButCannotClaimAnAdditionalTime() {
        val initial = request()
        val missing = DrivingLegKey(origin.id, home.id)
        val plan = planner.compare(initial.copy(drivingEstimates = initial.drivingEstimates - missing)).single()
        assertFalse(plan.isValid)
        assertEquals(1101, plan.driverHomeArrivalMinute)
        assertEquals(1090, plan.passengerArrivals.single().dropoffMinute)
        assertNull(plan.driverAdditionalMinutes)
        assertEquals(listOf(SharedPlanIssue.MissingDrivingEstimate(missing)), plan.issues)
    }

    @Test fun aMissingLaterLegDoesNotEraseAnAlreadyKnownPassengerArrival() {
        val initial = request(errand = errand)
        val missing = DrivingLegKey(station.id, shop.id)
        val plans = planner.compare(initial.copy(drivingEstimates = initial.drivingEstimates - missing))
        val after = plans.single { it.stops.first() is SharedScheduledStop.Dropoff }
        assertEquals(1090, after.passengerArrivals.single().dropoffMinute)
        assertNull(after.driverHomeArrivalMinute)
        assertTrue(after.issues.contains(SharedPlanIssue.MissingDrivingEstimate(missing)))
    }

    @Test fun aVerifiedCandidateIsPreferredToAnUnverifiedFasterCandidate() {
        val unverified = candidate.copy(id = "unverified", place = otherStation, verification = DropoffVerification())
        val plans = planner.compare(request(listOf(passenger.copy(candidates = listOf(candidate, unverified))), overrides = mapOf(
            DrivingLegKey(origin.id, otherStation.id) to SharedDrivingEstimate(1, entered),
            DrivingLegKey(otherStation.id, home.id) to SharedDrivingEstimate(1, entered),
        )))
        assertTrue(plans.first().isValid)
        assertEquals(candidate.id, plans.first().passengerArrivals.single().candidate.id)
        assertEquals(SharedPlanIssue.UnverifiedDropoff(passenger.id, unverified.id), plans.last().issues.single())
    }

    @Test fun anUnsuitableVehicleApproachCannotBeRecommendedAsVerified() {
        val unsuitable = candidate.copy(verification = checked.copy(vehicleAccess = AccessCheck.UNSUITABLE))
        val plan = planner.compare(request(listOf(passenger.copy(candidates = listOf(unsuitable))))).single()
        assertFalse(plan.isValid)
        assertEquals(listOf(SharedPlanIssue.UnsuitableDropoff(passenger.id, candidate.id)), plan.issues)
    }

    @Test fun errandWalkingIncludesReturnAndItsDeadlineUsesServiceCompletion() {
        val walkAndCollect = errand.copy(visit = errand.visit.copy(
            walkingMinutesOneWay = 2, stayMinutes = 5, deadlineMinute = 1097,
        ))
        val plan = planner.compare(request(errand = walkAndCollect)).single { it.stops.first() is SharedScheduledStop.Errand }
        val stop = plan.stops.first() as SharedScheduledStop.Errand
        assertEquals(1097, stop.serviceFinishedMinute)
        assertEquals(1099, stop.departureMinute)
        assertEquals(10, plan.stopMinutes)
        assertFalse(plan.issues.any { it is SharedPlanIssue.ErrandDeadline })
    }

    @Test fun notReadyErrandAndMissedServiceDeadlineHaveSeparateReasons() {
        val notReady = errand.copy(visit = errand.visit.copy(ready = false, deadlineMinute = 1099))
        val plan = planner.compare(request(errand = notReady)).single { it.stops.first() is SharedScheduledStop.Errand }
        assertTrue(plan.issues.contains(SharedPlanIssue.ErrandNotReady))
        assertTrue(plan.issues.contains(SharedPlanIssue.ErrandDeadline(1)))
    }

    @Test fun absoluteMinutesKeepAnExplicitNextDayDeadlineAcrossMidnight() {
        val lateDeparture = 1430
        val nextDay = passenger.copy(latestArrivalMinute = 1440)
        val plan = planner.compare(request(listOf(nextDay), departure = lateDeparture)).single()
        assertEquals(1440, plan.passengerArrivals.single().dropoffMinute)
        assertTrue(plan.isValid)
        val sameDay = nextDay.copy(latestArrivalMinute = 13)
        assertFalse(planner.compare(request(listOf(sameDay), departure = lateDeparture)).single().isValid)
    }

    @Test fun routeAndSampleSourcesStayDistinguishableInThePlan() {
        val live = TravelEvidence(TravelEstimateKind.ROUTING_SERVICE, "경로 응답", 1234L, includesLiveTraffic = true)
        val sample = TravelEvidence(TravelEstimateKind.SAMPLE, "전시 예시")
        val plan = planner.compare(request(overrides = mapOf(
            DrivingLegKey(origin.id, station.id) to SharedDrivingEstimate(10, live),
            DrivingLegKey(station.id, home.id) to SharedDrivingEstimate(10, sample),
        ))).single()
        assertTrue(plan.includesSampleTimes)
        assertTrue(plan.includesUserEstimates)
        assertTrue(plan.evidence.contains(live))
        assertEquals(1234L, plan.evidence.single { it.kind == TravelEstimateKind.ROUTING_SERVICE }.observedAtMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun routeServiceEvidenceRequiresAQueryTimestamp() {
        TravelEvidence(TravelEstimateKind.ROUTING_SERVICE, "경로 응답")
    }

    @Test(expected = IllegalArgumentException::class)
    fun onePlaceIdCannotReferToDifferentPhysicalPlaces() {
        val changed = candidate.copy(place = station.copy(point = station.point.copy(latitude = 38.0)))
        request(listOf(passenger.copy(candidates = listOf(candidate, changed.copy(id = "changed")))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun moreThanTwoPassengersAreRejected() {
        request(listOf(passenger, passenger.copy(id = "two"), passenger.copy(id = "three")))
    }
}
