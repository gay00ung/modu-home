package io.moduhome.app.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleLocationTest {
    private val receivedAt = 1_800_000_000_123L

    @Test fun preservesTheReturnedCoordinateAddressAndReceiptTime() {
        val result = observation(37.566675, 126.97819444, "서울특별시 중구") as VehicleLocationResult.Available
        assertEquals("현재 차량 위치", result.location.point.name)
        assertEquals(37.566675, result.location.point.latitude, 0.0)
        assertEquals(126.97819444, result.location.point.longitude, 0.0)
        assertEquals("서울특별시 중구", result.location.point.address)
        assertEquals(receivedAt, result.location.receivedAtEpochMillis)
    }

    @Test fun aCoordinateDoesNotAcquireDestinationPoiIdentifiers() {
        val result = observation(37.5, 127.0) as VehicleLocationResult.Available
        assertEquals("", result.location.point.placeId)
        assertEquals("", result.location.point.subId)
        assertEquals("", result.location.point.provider)
        assertEquals("", result.location.point.address)
    }

    @Test fun rejectsBothZeroCoordinatesIncludingNegativeZero() {
        assertTrue(observation(0.0, 0.0) is VehicleLocationResult.Failed)
        assertTrue(observation(-0.0, 0.0) is VehicleLocationResult.Failed)
        assertTrue(observation(0.0, -0.0) is VehicleLocationResult.Failed)
    }

    @Test fun acceptsTheEquatorAndPrimeMeridianWhenTheOtherCoordinateIsPresent() {
        assertTrue(observation(0.0, 127.0) is VehicleLocationResult.Available)
        assertTrue(observation(37.5, 0.0) is VehicleLocationResult.Available)
    }

    @Test fun rejectsNonFiniteValuesOnEitherAxis() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertTrue(observation(invalid, 127.0) is VehicleLocationResult.Failed)
            assertTrue(observation(37.5, invalid) is VehicleLocationResult.Failed)
        }
    }

    @Test fun rejectsCoordinatesOutsideGeographicRanges() {
        assertTrue(observation(-90.0001, 127.0) is VehicleLocationResult.Failed)
        assertTrue(observation(90.0001, 127.0) is VehicleLocationResult.Failed)
        assertTrue(observation(37.5, -180.0001) is VehicleLocationResult.Failed)
        assertTrue(observation(37.5, 180.0001) is VehicleLocationResult.Failed)
    }

    @Test fun acceptsValidBoundaryCoordinatesWithoutChangingThem() {
        assertTrue(observation(-90.0, -180.0) is VehicleLocationResult.Available)
        assertTrue(observation(90.0, 180.0) is VehicleLocationResult.Available)
    }

    @Test fun rejectsAnInvalidReceiptTimeWithoutInventingAnotherTimestamp() {
        assertTrue(VehicleLocation.fromObservation(37.5, 127.0, "", -1) is VehicleLocationResult.Failed)
    }

    @Test fun copyingCannotTurnAValidLocationIntoAnUnavailableCoordinate() {
        val location = (observation(37.5, 127.0) as VehicleLocationResult.Available).location
        assertThrows(IllegalArgumentException::class.java) {
            location.copy(point = NavigationPoint("위치", 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) { location.copy(receivedAtEpochMillis = -1) }
    }

    private fun observation(latitude: Double, longitude: Double, address: String = "") =
        VehicleLocation.fromObservation(latitude, longitude, address, receivedAt)
}
