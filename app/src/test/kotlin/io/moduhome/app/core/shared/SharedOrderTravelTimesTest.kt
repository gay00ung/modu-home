package io.moduhome.app.core.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.ErrandVisit
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.*
import org.junit.Test

class SharedOrderTravelTimesTest {
    private val oldMatrixEvidence = TravelEvidence(TravelEstimateKind.SAMPLE, "이전 행렬")
    private val routeEvidence = TravelEvidence(TravelEstimateKind.ROUTING_SERVICE, "방문 순서별 테스트 응답", 1000L)
    private val checked = DropoffVerification(AccessCheck.SUITABLE, "테스트 확인 자료", 1L)
    private val origin = place("origin", 37.50)
    private val home = place("home", 37.51)
    private val a = place("a", 37.52)
    private val b = place("b", 37.53)
    private val shop = place("shop", 37.54)
    private val first = SharedPassenger("first", "동승자 1", listOf(SharedDropoffCandidate("a", a, checked)))
    private val second = SharedPassenger("second", "동승자 2", listOf(SharedDropoffCandidate("b", b, checked)))
    private val errand = SharedErrand(shop, ErrandVisit(VisitKind.PICKUP, "픽업", 10))
    private val planner = SharedJourneyPlanner()

    private fun place(id: String, latitude: Double) = SharedJourneyPlace(id, NavigationPoint(id, latitude, 127.0))

    private fun request(
        passengers: List<SharedPassenger> = listOf(first, second),
        visit: SharedErrand? = errand,
    ): SharedJourneyRequest {
        val places = (listOf(origin, home) + passengers.flatMap { passenger -> passenger.candidates.map { it.place } } +
            listOfNotNull(visit?.place)).distinctBy { it.id }
        val oldMatrix = places.flatMap { from ->
            places.filter { it.id != from.id }.map { to ->
                DrivingLegKey(from.id, to.id) to SharedDrivingEstimate(480, oldMatrixEvidence)
            }
        }.toMap()
        return SharedJourneyRequest(origin, home, 1080, 60, passengers, oldMatrix, visit)
    }

    private fun direct(request: SharedJourneyRequest, minutes: Int = 20) =
        SharedOrderedDrivingLeg(request.origin, request.home, SharedDrivingEstimate(minutes, routeEvidence))

    private fun responses(
        request: SharedJourneyRequest,
        minutes: (SharedJourneyOrder, Int) -> Int = { _, _ -> 10 },
    ): List<SharedOrderTravelEstimate> = planner.orders(request).map { order ->
        SharedOrderTravelEstimate(order, order.places.zipWithNext().mapIndexed { index, (from, to) ->
            SharedOrderedDrivingLeg(from, to, SharedDrivingEstimate(minutes(order, index), routeEvidence))
        })
    }

    private fun bundle(request: SharedJourneyRequest, estimates: List<SharedOrderTravelEstimate> = responses(request)) =
        requireNotNull(SharedOrderTravelTimes.create(request, direct(request), estimates))

    @Test fun twoPassengersAndAnErrandKeepAllSixOrdersAndTheirOwnFourLegs() {
        val request = request()
        val orders = planner.orders(request)
        assertEquals(6, orders.size)
        assertEquals(6, orders.map { it.id }.distinct().size)
        assertTrue(orders.all { it.stopIds.size == 3 && it.places.size == 5 })
        assertTrue(orders.all { it.places.first() == origin && it.places.last() == home })
        val estimates = responses(request) { order, index -> orders.indexOf(order) + index + 1 }
        val plans = planner.compare(request, bundle(request, estimates))
        assertEquals(orders.map { it.id }.toSet(), plans.map { it.id }.toSet())
        estimates.forEach { response ->
            val plan = plans.single { it.id == response.order.id }
            assertEquals(response.legs.sumOf { it.estimate.minutes }, plan.drivingMinutes)
            assertEquals(1080 + response.legs.sumOf { it.estimate.minutes } + 12, plan.driverHomeArrivalMinute)
        }
    }

    @Test fun orderedModeNeverReadsTheOldDrivingMatrixOrItsDirectHomeTime() {
        val request = request()
        val plans = planner.compare(request, bundle(request))
        assertTrue(plans.all { it.drivingMinutes == 40 })
        assertTrue(plans.all { it.driverHomeArrivalMinute == 1132 })
        assertTrue(plans.all { it.driverAdditionalMinutes == 32 })
        assertTrue(plans.all { it.isValid })
        assertTrue(plans.all { routeEvidence in it.evidence })
        assertTrue(plans.none { oldMatrixEvidence in it.evidence || it.includesSampleTimes })
    }

    @Test fun orderedModeAlsoWorksWhenTheLegacyMatrixIsEntirelyAbsent() {
        val request = request().copy(drivingEstimates = emptyMap())
        assertTrue(planner.compare(request, bundle(request)).all { it.isValid && it.drivingMinutes == 40 })
        assertTrue(planner.compare(request).all { !it.isValid && it.drivingMinutes == null })
    }

    @Test fun theSamePhysicalLegCanHaveDifferentTimesInDifferentWholeOrders() {
        val request = request()
        val estimates = responses(request) { order, index ->
            val from = order.places[index].id
            val to = order.places[index + 1].id
            if (from == a.id && to == b.id) {
                if (order.places[1] == a) 3 else 17
            } else 2
        }
        val plans = planner.compare(request, bundle(request, estimates))
        val aThenB = plans.single { plan -> plan.stops.map { it.place.id } == listOf(a.id, b.id, shop.id) }
        val errandThenAThenB = plans.single { plan -> plan.stops.map { it.place.id } == listOf(shop.id, a.id, b.id) }
        assertEquals(9, aThenB.drivingMinutes)
        assertEquals(23, errandThenAThenB.drivingMinutes)
    }

    @Test fun anErrandAfterDropoffCannotDelayThePassengersOwnArrival() {
        val request = request()
        val plans = planner.compare(request, bundle(request))
        val aThenErrandThenB = plans.single { it.stops.map { stop -> stop.place.id } == listOf(a.id, shop.id, b.id) }
        val aThenBThenErrand = plans.single { it.stops.map { stop -> stop.place.id } == listOf(a.id, b.id, shop.id) }
        assertEquals(1090, aThenErrandThenB.passengerArrivals.single { it.passengerId == first.id }.dropoffMinute)
        assertEquals(
            aThenBThenErrand.passengerArrivals.single { it.passengerId == first.id }.dropoffMinute,
            aThenErrandThenB.passengerArrivals.single { it.passengerId == first.id }.dropoffMinute,
        )
        assertEquals(1121, aThenErrandThenB.passengerArrivals.single { it.passengerId == second.id }.dropoffMinute)
        assertEquals(1101, aThenBThenErrand.passengerArrivals.single { it.passengerId == second.id }.dropoffMinute)
    }

    @Test fun missingAnEntireOrderDoesNotProduceAPartialSetDespiteACompleteLegacyMatrix() {
        val request = request()
        assertNull(SharedOrderTravelTimes.create(request, direct(request), responses(request).dropLast(1)))
    }

    @Test fun missingTheHomeLegDoesNotBorrowItFromTheLegacyMatrix() {
        val request = request()
        val estimates = responses(request).toMutableList()
        estimates[0] = estimates[0].copy(legs = estimates[0].legs.dropLast(1))
        assertNull(SharedOrderTravelTimes.create(request, direct(request), estimates))
    }

    @Test fun missingTheDirectRouteDoesNotBorrowTheLegacyDirectTime() {
        val request = request()
        assertNull(SharedOrderTravelTimes.create(request, null, responses(request)))
    }

    @Test fun swappedLegsAreRejectedEvenWhenTheirTotalTimeAndCountMatch() {
        val request = request()
        val estimates = responses(request).toMutableList()
        val legs = estimates[0].legs.toMutableList()
        val firstLeg = legs[0]
        legs[0] = legs[1]
        legs[1] = firstLeg
        estimates[0] = estimates[0].copy(legs = legs)
        assertNull(SharedOrderTravelTimes.create(request, direct(request), estimates))
    }

    @Test fun relabellingOneOrdersResponseAsAnotherIsRejected() {
        val request = request()
        val estimates = responses(request).toMutableList()
        val firstOrder = estimates[0].order
        val secondOrder = estimates[1].order
        estimates[0] = estimates[0].copy(order = secondOrder)
        estimates[1] = estimates[1].copy(order = firstOrder)
        assertNull(SharedOrderTravelTimes.create(request, direct(request), estimates))
    }

    @Test fun aDuplicateResponseCannotStandInForTheMissingOrder() {
        val request = request()
        val estimates = responses(request).toMutableList()
        estimates[estimates.lastIndex] = estimates.first()
        assertNull(SharedOrderTravelTimes.create(request, direct(request), estimates))
    }

    @Test fun aMovedCoordinateWithTheSamePlaceIdIsRejected() {
        val request = request()
        val estimates = responses(request).toMutableList()
        val changed = estimates[0].legs[0].to.let { it.copy(point = it.point.copy(latitude = it.point.latitude + 0.1)) }
        estimates[0] = estimates[0].copy(legs = estimates[0].legs.toMutableList().also { legs ->
            legs[0] = legs[0].copy(to = changed)
        })
        assertNull(SharedOrderTravelTimes.create(request, direct(request), estimates))
    }

    @Test fun aDirectRouteToADifferentHomeIsRejected() {
        val request = request()
        assertNull(SharedOrderTravelTimes.create(request, direct(request).copy(to = shop), responses(request)))
    }

    @Test fun allCandidateCombinationsMustBePresentAsWellAsAllOrders() {
        val alternate = first.candidates.single().copy(id = "alternate", place = place("alternate", 37.55))
        val request = request(listOf(first.copy(candidates = first.candidates + alternate), second))
        val estimates = responses(request)
        assertEquals(12, estimates.size)
        assertNull(SharedOrderTravelTimes.create(request, direct(request), estimates.take(6)))
        assertEquals(12, planner.compare(request, bundle(request, estimates)).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aValidatedSetCannotBeReusedAfterTheHomeChanges() {
        val request = request()
        val travel = bundle(request)
        planner.compare(request.copy(home = home.copy(point = home.point.copy(latitude = 37.59))), travel)
    }

    @Test fun unrelatedConditionChangesCanReuseTheSameOrderedRoadLegs() {
        val request = request()
        val travel = bundle(request)
        val changed = request.copy(allowedDriverAdditionalMinutes = 31)
        val plans = planner.compare(changed, travel)
        assertTrue(plans.all { it.issues.contains(SharedPlanIssue.DriverOverBudget(1)) })
    }

    @Test fun adjacentPassengersAtOnePlaceStillNeedTheirExplicitZeroMinuteLeg() {
        val secondAtA = second.copy(candidates = listOf(first.candidates.single()))
        val request = request(listOf(first, secondAtA), visit = null)
        val estimates = responses(request) { order, index ->
            if (order.places[index].id == order.places[index + 1].id) 0 else 10
        }
        assertTrue(planner.compare(request, bundle(request, estimates)).all { it.drivingMinutes == 20 })
        val missing = estimates.toMutableList()
        missing[0] = missing[0].copy(legs = missing[0].legs.filter { it.from.id != it.to.id })
        assertNull(SharedOrderTravelTimes.create(request, direct(request), missing))
    }

    @Test fun mutatingTheCallerResponseListCannotChangeAValidatedSet() {
        val request = request()
        val estimates = responses(request).toMutableList()
        val mutableLegs = estimates[0].legs.toMutableList()
        estimates[0] = estimates[0].copy(legs = mutableLegs)
        val travel = bundle(request, estimates)
        mutableLegs.clear()
        estimates.clear()
        assertEquals(6, planner.compare(request, travel).size)
        assertTrue(planner.compare(request, travel).all { it.drivingMinutes == 40 })
    }
}
