package io.moduhome.app.core.planning

import org.junit.Assert.*
import org.junit.Test

class ErrandJourneyPlannerTest {
    private val pickup = ErrandVisit(VisitKind.PICKUP, "픽업 장소", 5)
    private val groceries = ErrandVisit(VisitKind.GROCERIES, "장보기 장소", 15)
    private fun request(visits: List<ErrandVisit> = listOf(pickup, groceries), budget: Int = 30, departure: Int = 1090) =
        ErrandJourneyRequest("출발지", "집", departure, budget, visits, TravelEstimates())

    @Test fun bothOrdersUseTheirOwnDrivingLegs() {
        val plans = ErrandJourneyPlanner().compare(request())
        assertEquals(2, plans.size)
        val first = plans.first()
        assertEquals(listOf(VisitKind.PICKUP, VisitKind.GROCERIES), first.visits.map { it.visit.kind })
        assertEquals(43, first.drivingMinutes)
        assertEquals(20, first.stayMinutes)
        assertEquals(63, first.totalMinutes)
        assertEquals(23, first.additionalMinutes)
        assertEquals(1153, first.homeArrivalMinute)
        assertTrue(first.isValid)
        assertEquals(68, plans.last().drivingMinutes)
        assertEquals(48, plans.last().additionalMinutes)
        assertEquals(PlanIssueKind.OVER_BUDGET, plans.last().issues.single().kind)
    }

    @Test fun singlePickupHasOnePlanAndSevenAdditionalMinutes() {
        val plans = ErrandJourneyPlanner().compare(request(listOf(pickup)))
        assertEquals(1, plans.size)
        assertEquals(7, plans.single().additionalMinutes)
    }

    @Test fun walkingIncludesTheTripBackToTheCar() {
        val plan = ErrandJourneyPlanner().compare(request(listOf(pickup.copy(walkingMinutesOneWay = 2), groceries.copy(walkingMinutesOneWay = 3)))).first()
        assertEquals(10, plan.walkingMinutes)
        assertEquals(73, plan.totalMinutes)
        assertEquals(1109, plan.visits.first().serviceFinishedMinute)
        assertEquals(1111, plan.visits.first().departureMinute)
        assertFalse(plan.isValid)
    }

    @Test fun aSlowerOrderCanBeRecommendedWhenItMeetsTheClosingTime() {
        val plans = ErrandJourneyPlanner().compare(request(listOf(pickup, groceries.copy(deadlineMinute = 1130)), budget = 60))
        assertEquals(VisitKind.GROCERIES, plans.first().visits.first().visit.kind)
        assertTrue(plans.first().isValid)
        assertEquals(88, plans.first().totalMinutes)
        assertTrue(plans.last().issues.any { it.kind == PlanIssueKind.DEADLINE && it.minutes == 6 })
    }

    @Test fun pickupMustBeReadyEvenWhenTheTimeBudgetFits() {
        val plans = ErrandJourneyPlanner().compare(request(listOf(pickup.copy(ready = false)), budget = 60))
        assertEquals(PlanIssueKind.NOT_READY, plans.single().issues.single().kind)
    }

    @Test fun completingServiceAtTheDeadlineIsAllowed() {
        val plan = ErrandJourneyPlanner().compare(request(listOf(pickup.copy(deadlineMinute = 1107)))).single()
        assertTrue(plan.isValid)
    }

    @Test fun exactAdditionalTimeLimitIsAllowed() {
        val plan = ErrandJourneyPlanner().compare(request(budget = 23)).first()
        assertTrue(plan.isValid)
    }

    @Test fun anExplicitNextDayDeadlineSurvivesMidnight() {
        val plan = ErrandJourneyPlanner().compare(request(listOf(pickup.copy(deadlineMinute = 1450)), departure = 1430)).single()
        assertTrue(plan.isValid)
        assertEquals("00:37 (+1일)", formatJourneyMinute(plan.homeArrivalMinute))
    }

    @Test fun aDeadlineOnTheDepartureDayIsNotSilentlyMovedToTomorrow() {
        val plan = ErrandJourneyPlanner().compare(request(listOf(pickup.copy(deadlineMinute = 10)), departure = 1430)).single()
        assertTrue(plan.issues.any { it.kind == PlanIssueKind.DEADLINE })
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateVisitKindsAreRejected() { request(listOf(pickup, pickup)) }
}
