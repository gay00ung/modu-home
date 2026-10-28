package io.moduhome.app.core.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeBudgetTest {
    @Test
    fun storeStayCanMakeAnOtherwiseShortDetourExceedTheBudget() {
        val result = TimeBudget(40, 20).evaluate(plannedDrivingMinutes = 48, stayMinutes = 15)
        assertEquals(63, result.totalMinutes)
        assertEquals(23, result.additionalMinutes)
        assertFalse(result.isWithinBudget)
    }

    @Test
    fun walkingFromParkingIsIncludedInTheArrivalBudget() {
        val result = TimeBudget(40, 10).evaluate(
            plannedDrivingMinutes = 48,
            stayMinutes = 0,
            walkingMinutes = 5,
        )
        assertEquals(13, result.additionalMinutes)
        assertFalse(result.isWithinBudget)
    }

    @Test
    fun anArrivalExactlyAtTheAllowedLimitIsAccepted() {
        val result = TimeBudget(40, 23).evaluate(plannedDrivingMinutes = 48, stayMinutes = 15)
        assertTrue(result.isWithinBudget)
    }

    @Test
    fun aFasterRouteDoesNotProduceNegativeAdditionalTime() {
        val result = TimeBudget(40, 0).evaluate(plannedDrivingMinutes = 38, stayMinutes = 0)
        assertEquals(0, result.additionalMinutes)
        assertTrue(result.isWithinBudget)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeStayTimeIsRejected() {
        TimeBudget(40, 20).evaluate(plannedDrivingMinutes = 48, stayMinutes = -1)
    }
}
