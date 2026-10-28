package io.moduhome.app.core.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutedJourneyPlannerTest {
    private val pickup = ErrandVisit(VisitKind.PICKUP, "픽업 장소", 5)
    private val groceries = ErrandVisit(VisitKind.GROCERIES, "장보기 장소", 15)
    private val planner = ErrandJourneyPlanner()

    @Test fun eachCandidateKeepsItsOwnLegsEvenWhenItVisitsTheSamePlace() {
        val times = RoutedJourneyTimes(10, mapOf(
            "PICKUP" to listOf(5, 7),
            "PICKUP>GROCERIES" to listOf(8, 9, 10),
            "GROCERIES>PICKUP" to listOf(11, 12, 13),
        ))
        val plans = planner.compare(request(), times).associateBy { it.id }
        val pickupFirst = plans.getValue("PICKUP>GROCERIES")
        val groceriesFirst = plans.getValue("GROCERIES>PICKUP")
        assertEquals(27, pickupFirst.drivingMinutes)
        assertEquals(36, groceriesFirst.drivingMinutes)
        assertEquals(1098, pickupFirst.visits.first().arrivalMinute)
        assertEquals(1101, groceriesFirst.visits.first().arrivalMinute)
        val pickupOnly = planner.compare(request(listOf(pickup)), times).single()
        assertEquals(12, pickupOnly.drivingMinutes)
        assertEquals(1095, pickupOnly.visits.single().arrivalMinute)
    }

    @Test fun rejectsMissingCandidatesUnknownKindsAndMalformedLegLists() {
        val onlyOneOrder = RoutedJourneyTimes(10, mapOf("PICKUP>GROCERIES" to listOf(1, 2, 3)))
        assertThrows(IllegalArgumentException::class.java) { planner.compare(request(), onlyOneOrder) }
        assertThrows(IllegalArgumentException::class.java) { RoutedJourneyTimes(10, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { RoutedJourneyTimes(10, mapOf("GROCERY" to listOf(1, 2))) }
        assertThrows(IllegalArgumentException::class.java) { RoutedJourneyTimes(10, mapOf("PICKUP>PICKUP" to listOf(1, 2, 3))) }
        assertThrows(IllegalArgumentException::class.java) { RoutedJourneyTimes(10, mapOf("PICKUP" to listOf(1))) }
        assertThrows(IllegalArgumentException::class.java) { RoutedJourneyTimes(10, mapOf("PICKUP>GROCERIES" to listOf(1, 2))) }
        assertThrows(IllegalArgumentException::class.java) { RoutedJourneyTimes(10, mapOf("PICKUP" to listOf(-1, 2))) }
        assertThrows(IllegalArgumentException::class.java) { RoutedJourneyTimes(10, mapOf("PICKUP" to listOf(1, 481))) }
    }

    @Test fun additionalTimeUsesTheQueriedDirectRouteInsteadOfTheManualBaseline() {
        val times = RoutedJourneyTimes(10, mapOf(
            "PICKUP>GROCERIES" to listOf(5, 10, 15),
            "GROCERIES>PICKUP" to listOf(7, 9, 20),
        ))
        val plan = planner.compare(request(budget = 30), times).first()
        assertEquals(40, request().travel.directHome)
        assertEquals(50, plan.totalMinutes)
        assertEquals(40, plan.additionalMinutes)
        assertFalse(plan.isValid)
        assertEquals(10, plan.issues.single { it.kind == PlanIssueKind.OVER_BUDGET }.minutes)
    }

    @Test fun theSharedScheduleChecksDeadlinesAfterWalkingAndServiceOnTheQueriedLegs() {
        val visits = listOf(
            pickup.copy(walkingMinutesOneWay = 2),
            groceries.copy(walkingMinutesOneWay = 3, deadlineMinute = 1130),
        )
        val times = RoutedJourneyTimes(20, mapOf(
            "PICKUP>GROCERIES" to listOf(20, 2, 2),
            "GROCERIES>PICKUP" to listOf(5, 30, 20),
        ))
        val plans = planner.compare(request(visits, budget = 100), times)
        assertEquals("GROCERIES>PICKUP", plans.first().id)
        assertTrue(plans.first().isValid)
        assertEquals(85, plans.first().totalMinutes)
        val late = plans.last()
        assertEquals(54, late.totalMinutes)
        assertEquals(10, late.walkingMinutes)
        assertEquals(1139, late.visits.last().serviceFinishedMinute)
        assertEquals(9, late.issues.single { it.kind == PlanIssueKind.DEADLINE }.minutes)
    }

    @Test fun routedLegsAllowZeroThrough480WithoutChangingManualTravelLimits() {
        val longRoute = RoutedJourneyTimes(480, mapOf("PICKUP" to listOf(480, 480)))
        val plan = planner.compare(request(listOf(pickup), budget = 240), longRoute).single()
        assertEquals(960, plan.drivingMinutes)
        assertEquals(485, plan.additionalMinutes)
        assertFalse(plan.isValid)
        val zeroRoute = RoutedJourneyTimes(0, mapOf("PICKUP" to listOf(0, 0)))
        val zeroPlan = planner.compare(request(listOf(pickup.copy(stayMinutes = 0))), zeroRoute).single()
        assertEquals(0, zeroPlan.totalMinutes)
        assertTrue(zeroPlan.isValid)
        assertThrows(IllegalArgumentException::class.java) { TravelEstimates(directHome = 480) }
        assertThrows(IllegalArgumentException::class.java) { RoutedJourneyTimes(481, mapOf("PICKUP" to listOf(1, 2))) }
    }

    private fun request(
        visits: List<ErrandVisit> = listOf(pickup, groceries),
        budget: Int = 100,
    ) = ErrandJourneyRequest("출발지", "집", 1090, budget, visits, TravelEstimates())
}
