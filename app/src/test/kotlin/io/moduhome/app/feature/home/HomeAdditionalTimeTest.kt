package io.moduhome.app.feature.home

import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import org.junit.Assert.*
import org.junit.Test

class HomeAdditionalTimeTest {
    private fun compared(): HomeUiState {
        val draft = JourneyDraft(budget = 20).let { it.copy(pickup = it.pickup.copy(ready = true)) }
        val state = HomeUiState(draft)
        return state.copy(progress = state.progress.compare(state.plans))
    }

    @Test fun explicitChoiceChangesOnlyTheBudgetAndReturnsToInputForFreshComparison() {
        val old = compared()
        val suggestion = requireNotNull(old.additionalTimeSuggestion)
        val next = requireNotNull(old.withAdditionalTime(suggestion))
        assertEquals(old.draft.copy(budget = 23), next.draft)
        assertEquals(JourneyProgress(), next.progress)
        assertNull(next.routing)
        assertEquals(old.acceptedProposals, next.acceptedProposals)
        assertTrue(next.plans.any { it.isValid })
        assertNull(next.additionalTimeSuggestion)
    }

    @Test fun staleChoiceCannotOverwriteChangedConditions() {
        val old = compared()
        val suggestion = requireNotNull(old.additionalTimeSuggestion)
        val changed = old.copy(draft = old.draft.copy(pickup = old.draft.pickup.copy(ready = false)))
        assertNull(changed.withAdditionalTime(suggestion))
        assertNull(old.copy(draft = old.draft.copy(budget = 10)).withAdditionalTime(suggestion))
    }

    @Test fun choiceOutsideComparisonNeverResetsAnExistingJourney() {
        val old = compared()
        val suggestion = requireNotNull(old.additionalTimeSuggestion)
        for (stage in JourneyStage.entries.filter { it != JourneyStage.COMPARE }) {
            val state = old.copy(progress = old.progress.copy(stage = stage))
            assertNull(state.additionalTimeSuggestion)
            assertNull(state.withAdditionalTime(suggestion))
        }
    }
}
