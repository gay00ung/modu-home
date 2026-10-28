package io.moduhome.app.feature.shared

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.OnwardTravelMode
import io.moduhome.app.core.shared.TravelEstimateKind
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SharedJourneyDraftTest {
    private val now = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val origin = NavigationPoint("현재 차량", 37.50, 127.00)
    private val driverHome = NavigationPoint("운전자 집", 37.51, 127.01)
    private val passengerHome = NavigationPoint("동승자 집", 37.52, 127.02)
    private val passenger = SharedPassengerDraft("p1", "동승자 1", passengerHome,
        doorstepDropoffConfirmed = true, doorstepConfirmedAtMillis = now - 1000)
    private val draft = SharedJourneyDraft(driverHome, listOf(passenger))

    @Test fun anExplicitDoorstepConditionCreatesWalkingZeroWithoutInventingATransitRoute() {
        val result = draft.validate(origin, now, zone)
        assertTrue(result.errors.isEmpty())
        val request = requireNotNull(result.request)
        val candidate = request.passengers.single().candidates.single()
        assertEquals(1080, request.departureMinute)
        assertEquals(driverHome, request.home.point)
        assertEquals(passengerHome, candidate.place.point)
        assertEquals(OnwardTravelMode.WALK, candidate.onward.mode)
        assertEquals(0, candidate.onward.totalMinutes)
        assertEquals(0, candidate.onward.walkingMinutes)
        assertEquals(TravelEstimateKind.USER_INPUT, candidate.onward.evidence.kind)
        assertTrue(request.drivingEstimates.isEmpty())
    }

    @Test fun selectingAPlaceDoesNotSilentlyConfirmItsAccess() {
        val candidate = requireNotNull(draft.validate(origin, now, zone).request).passengers.single().candidates.single()
        assertEquals(AccessCheck.NOT_CHECKED, candidate.verification.vehicleAccess)
        assertEquals(AccessCheck.NOT_CHECKED, candidate.verification.pedestrianConnection)
        assertNull(candidate.verification.checkedAtMillis)
        assertEquals("", candidate.verification.source)
    }

    @Test fun anExplicitUserAccessRecordIsPreservedVerbatim() {
        val verification = DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "사용자 확인", now - 500)
        val request = requireNotNull(draft.copy(passengers = listOf(passenger.copy(verification = verification)))
            .validate(origin, now, zone).request)
        assertEquals(verification, request.passengers.single().candidates.single().verification)
    }

    @Test fun missingDoorstepConfirmationDoesNotCreateAnAssumedZeroMinuteOnwardJourney() {
        val result = draft.copy(passengers = listOf(passenger.copy(doorstepDropoffConfirmed = false)))
            .validate(origin, now, zone)
        assertNull(result.request)
        assertTrue(result.errors.containsKey("passengers.0.doorstep"))
    }

    @Test fun aMissingOrFutureDoorstepConfirmationTimestampIsRejected() {
        listOf(null, now + 1).forEach { timestamp ->
            val result = draft.copy(passengers = listOf(passenger.copy(doorstepConfirmedAtMillis = timestamp)))
                .validate(origin, now, zone)
            assertNull(result.request)
            assertTrue(result.errors.containsKey("passengers.0.doorstep"))
        }
    }

    @Test fun choosingADifferentDropoffClearsTheOldConditionAndAccessCheck() {
        val previous = passenger.copy(verification = DropoffVerification(AccessCheck.SUITABLE,
            AccessCheck.SUITABLE, "사용자 확인", now - 500))
        val changed = previous.chooseDropoff(passengerHome.copy(placeId = "different-entrance"))
        assertFalse(changed.doorstepDropoffConfirmed)
        assertNull(changed.doorstepConfirmedAtMillis)
        assertEquals(DropoffVerification(), changed.verification)
        assertEquals(previous, previous.chooseDropoff(passengerHome))
    }

    @Test fun missingRealLocationsAreReportedInsteadOfFilledWithExamples() {
        assertNull(draft.copy(driverHome = null).validate(origin, now, zone).request)
        val result = draft.copy(passengers = listOf(passenger.copy(dropoff = null))).validate(origin, now, zone)
        assertTrue(result.errors.containsKey("passengers.0.dropoff"))
        assertNull(result.request)
        val zero = origin.copy(latitude = 0.0, longitude = 0.0)
        assertNull(draft.validate(zero, now, zone).request)
    }

    @Test fun twoPassengersAndOneErrandKeepThreeDistinctLogicalStops() {
        val second = passenger.copy(id = "p2", name = "동승자 2", dropoff = passengerHome.copy(placeId = "p2-home"))
        val request = requireNotNull(draft.copy(passengers = listOf(passenger, second),
            errand = SharedErrandDraft(VisitKind.PICKUP, origin, stayMinutes = 10, ready = true))
            .validate(origin, now, zone).request)
        assertEquals(2, request.passengers.size)
        assertEquals(2, request.passengers.map { it.candidates.single().place.id }.distinct().size)
        assertEquals(VisitKind.PICKUP, request.errand?.visit?.kind)
        assertEquals(10, request.errand?.visit?.stayMinutes)
    }

    @Test fun duplicateOrMoreThanTwoPassengersAreInvalidInput() {
        assertNull(draft.copy(passengers = listOf(passenger, passenger)).validate(origin, now, zone).request)
        assertNull(draft.copy(passengers = listOf(passenger, passenger.copy(id = "p2"), passenger.copy(id = "p3")))
            .validate(origin, now, zone).request)
        assertNull(draft.copy(passengers = emptyList()).validate(origin, now, zone).request)
    }

    @Test fun nextDayEpochDeadlinesRemainExplicitNextDayMinutes() {
        val nextDay = Instant.parse("2026-10-28T15:30:00Z").toEpochMilli()
        val request = requireNotNull(draft.copy(passengers = listOf(passenger.copy(latestArrivalAtMillis = nextDay)),
            errand = SharedErrandDraft(VisitKind.GROCERIES, origin, deadlineAtMillis = nextDay))
            .validate(origin, now, zone).request)
        assertEquals(1470, request.passengers.single().latestArrivalMinute)
        assertEquals(1470, request.errand?.visit?.deadlineMinute)
        assertTrue(request.errand?.visit?.ready == true)
    }

    @Test fun elapsedOrUnsupportedFarFutureDeadlinesAreRejected() {
        listOf(now - 1, now + 48 * 60 * 60 * 1000L).forEach { deadline ->
            val result = draft.copy(passengers = listOf(passenger.copy(latestArrivalAtMillis = deadline)))
                .validate(origin, now, zone)
            assertNull(result.request)
            assertTrue(result.errors.containsKey("passengers.0.deadline"))
        }
    }

    @Test fun invalidStayWalkingAndBudgetAreNotClampedIntoAUsableRequest() {
        val invalid = draft.copy(allowedAdditionalMinutes = 241,
            errand = SharedErrandDraft(VisitKind.PICKUP, origin, stayMinutes = -1, walkingMinutesOneWay = 31))
            .validate(origin, now, zone)
        assertNull(invalid.request)
        assertTrue(invalid.errors.keys.containsAll(listOf("budget", "errand.stay", "errand.walking")))
    }
}
