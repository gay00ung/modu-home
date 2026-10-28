package io.moduhome.app.feature.home

import org.junit.Assert.*
import org.junit.Test

class JourneyDraftTest {
    @Test fun invalidClockCannotBecomeARoute() {
        val result = JourneyDraft(departure = "25:00").validate()
        assertNull(result.request)
        assertTrue(result.errors.containsKey("departure"))
    }

    @Test fun aBlankSelectedPlaceIsReportedWithoutThrowing() {
        val result = JourneyDraft(pickup = VisitDraft(place = "", stay = "5")).validate()
        assertNull(result.request)
        assertTrue(result.errors.containsKey("pickup.place"))
    }

    @Test fun unselectedErrandFieldsDoNotBlockThePlan() {
        val draft = JourneyDraft(groceries = VisitDraft(enabled = false, place = "", stay = ""),
            travel = TravelLeg.entries.associateWith { if (it == TravelLeg.GROCERIES_PICKUP) "" else it.initial })
        assertNotNull(draft.validate().request)
    }

    @Test fun atLeastOneVisitIsRequired() {
        val draft = JourneyDraft(pickup = VisitDraft(false, "픽업", "5"), groceries = VisitDraft(false, "마트", "15"))
        assertTrue(draft.validate().errors.containsKey("visits"))
    }

    @Test fun extremeDurationsAndMalformedDeadlinesAreRejected() {
        val result = JourneyDraft(pickup = VisitDraft(place = "픽업", stay = "999", deadline = "18:99")).validate()
        assertTrue(result.errors.containsKey("pickup.stay"))
        assertTrue(result.errors.containsKey("pickup.deadline"))
    }

    @Test fun tomorrowIsAnExplicitDeadlineChoice() {
        val result = JourneyDraft(pickup = VisitDraft(place = "픽업", stay = "5", deadline = "00:10", deadlineNextDay = true)).validate()
        assertEquals(1450, result.request?.visits?.first()?.deadlineMinute)
    }
    @Test fun pickupReadinessRequiresAnExplicitConfirmation() {
        assertFalse(JourneyDraft().pickup.ready)
    }

    @Test fun changingThePickupPlaceClearsItsPreviousConfirmation() {
        val confirmed = VisitDraft(place = "첫 픽업 장소", stay = "5", ready = true)
        assertFalse(confirmed.changePlace("다른 픽업 장소").ready)
        assertTrue(confirmed.changePlace("첫 픽업 장소 ").ready)
    }

    @Test fun aNewJourneyKeepsPlacesButRequiresPickupConfirmationAgain() {
        val previous = JourneyDraft(pickup = VisitDraft(place = "픽업 장소", stay = "5", ready = true))
        val next = previous.newJourney(20000, "19:20")
        assertEquals(previous.pickup.place, next.pickup.place)
        assertFalse(next.pickup.ready)
        assertEquals("19:20", next.departure)
    }

}
