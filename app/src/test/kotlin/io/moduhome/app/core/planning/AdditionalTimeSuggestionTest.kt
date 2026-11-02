package io.moduhome.app.core.planning

import org.junit.Assert.*
import org.junit.Test

class AdditionalTimeSuggestionTest {
    private val planner = ErrandJourneyPlanner()
    private val request = ErrandJourneyRequest("출발", "집", 1090, 20,
        listOf(ErrandVisit(VisitKind.PICKUP, "픽업", 5), ErrandVisit(VisitKind.GROCERIES, "장보기", 15)), TravelEstimates())

    @Test fun smallestBudgetThatPreservesTheOtherConditionsMakesAnOrderFeasible() {
        val suggestion = requireNotNull(planner.suggestAdditionalTime(planner.compare(request), 20))
        assertEquals(23, suggestion.requiredMinutes)
        assertEquals(3, suggestion.increaseMinutes)
        assertTrue(planner.compare(request.copy(allowedAdditionalMinutes = suggestion.requiredMinutes)).any { it.isValid })
    }

    @Test fun cheaperOrderThatMissesADeadlineCannotSetTheSuggestedBudget() {
        val bounded = request.copy(visits = request.visits.map {
            if (it.kind == VisitKind.GROCERIES) it.copy(deadlineMinute = 1130) else it
        })
        val suggestion = requireNotNull(planner.suggestAdditionalTime(planner.compare(bounded), 20))
        assertEquals(48, suggestion.requiredMinutes)
        val feasible = planner.compare(bounded.copy(allowedAdditionalMinutes = suggestion.requiredMinutes)).filter { it.isValid }
        assertEquals(VisitKind.GROCERIES, feasible.single().visits.first().visit.kind)
    }

    @Test fun unreadyPickupCannotBeSolvedBySpendingMoreTime() {
        val unready = request.copy(visits = request.visits.map { it.copy(ready = false) })
        assertNull(planner.suggestAdditionalTime(planner.compare(unready), 20))
    }

    @Test fun feasibleOrderDoesNotEncourageALargerBudget() {
        val valid = request.copy(allowedAdditionalMinutes = 23)
        assertNull(planner.suggestAdditionalTime(planner.compare(valid), 23))
    }

    @Test fun noRoutesAndInvalidBudgetDoNotCreateASuggestion() {
        assertNull(planner.suggestAdditionalTime(emptyList(), 20))
        assertNull(planner.suggestAdditionalTime(planner.compare(request), -1))
        assertNull(planner.suggestAdditionalTime(planner.compare(request), 241))
    }

    @Test fun exactBudgetLimitIsAllowedAndLargerCandidatesAreExcluded() {
        assertEquals(240, AdditionalTimeSuggestion.fromCandidates(239, listOf(241, 240))?.requiredMinutes)
        assertNull(AdditionalTimeSuggestion.fromCandidates(240, listOf(241)))
        assertNull(AdditionalTimeSuggestion.fromCandidates(20, listOf(20, 0, -1)))
    }
}
