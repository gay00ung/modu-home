package io.moduhome.app.core.planning

import org.junit.Assert.*
import org.junit.Test

class JourneyProgressTest {
    private val plans = ErrandJourneyPlanner().compare(ErrandJourneyRequest(
        "출발지", "집", 1090, 30,
        listOf(ErrandVisit(VisitKind.PICKUP, "픽업 장소", 5), ErrandVisit(VisitKind.GROCERIES, "마트", 15)),
        TravelEstimates(),
    ))
    private fun started() = JourneyProgress().compare(plans).review(plans).start(plans, 1000)

    @Test fun reviewIsRequiredBeforeStarting() {
        val compared = JourneyProgress().compare(plans)
        assertEquals(compared, compared.start(plans, 1000))
    }

    @Test fun invalidPlanCannotBeConfirmed() {
        val selected = JourneyProgress().compare(plans).select(plans.last().id, plans)
        assertEquals(JourneyStage.COMPARE, selected.review(plans).stage)
    }

    @Test fun allVisitsAndHomeArrivalMustBeConfirmedSeparately() {
        val active = started()
        assertEquals(active, active.finish(plans, 2000))
        val afterPickup = active.completeVisit(plans, VisitKind.PICKUP)
        assertEquals(1, afterPickup.completedVisits)
        assertEquals(afterPickup, afterPickup.finish(plans, 2000))
        val afterGroceries = afterPickup.completeVisit(plans, VisitKind.GROCERIES)
        assertEquals(JourneyStage.ACTIVE, afterGroceries.stage)
        val finished = afterGroceries.finish(plans, 3000)
        assertEquals(JourneyStage.COMPLETE, finished.stage)
        assertEquals(3000L, finished.completedAtMillis)
    }

    @Test fun repeatedCompletionCannotSkipPastTheVisitCount() {
        val state = started().completeVisit(plans, VisitKind.PICKUP).completeVisit(plans, VisitKind.GROCERIES).completeVisit(plans, VisitKind.GROCERIES)
        assertEquals(2, state.completedVisits)
    }

    @Test fun duplicateCompletionForOneVisitCannotCompleteTheNextVisit() {
        val once = started().completeVisit(plans, VisitKind.PICKUP)
        assertEquals(once, once.completeVisit(plans, VisitKind.PICKUP))
        assertEquals(1, once.completedVisits)
    }

    @Test fun backCannotDiscardAnActiveJourney() {
        val active = started().completeVisit(plans, VisitKind.PICKUP)
        assertEquals(active, active.back())
    }

    @Test fun returningFromReviewKeepsTheSelectedOrder() {
        val state = JourneyProgress().compare(plans).review(plans)
        assertEquals(JourneyStage.COMPARE, state.back().stage)
        assertEquals(state.selectedPlanId, state.back().selectedPlanId)
    }

    @Test fun editingClearsPreviousConfirmation() {
        assertEquals(JourneyProgress(), JourneyProgress().compare(plans).back())
    }
}
