package io.moduhome.app.core.navigation

/** A vehicle coordinate returned by navigation, with no implied accuracy or fix age. */
internal data class VehicleLocation(
    val point: NavigationPoint,
    /** Local callback receipt time, not the time at which the vehicle measured its position. */
    val receivedAtEpochMillis: Long,
) {
    init {
        require(point.latitude.isFinite() && point.longitude.isFinite())
        require(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
        require(point.latitude != 0.0 || point.longitude != 0.0)
        require(receivedAtEpochMillis >= 0)
    }

    companion object {
        fun fromObservation(
            latitude: Double,
            longitude: Double,
            address: String,
            receivedAtEpochMillis: Long,
        ): VehicleLocationResult {
            if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 ||
                longitude !in -180.0..180.0 || (latitude == 0.0 && longitude == 0.0)) {
                return VehicleLocationResult.Failed("차량 내비에서 현재 위치를 확인하지 못했어요")
            }
            if (receivedAtEpochMillis < 0) {
                return VehicleLocationResult.Failed("위치 응답의 수신 시각을 확인하지 못했어요")
            }
            return VehicleLocationResult.Available(VehicleLocation(
                point = NavigationPoint("현재 차량 위치", latitude, longitude, address = address),
                receivedAtEpochMillis = receivedAtEpochMillis,
            ))
        }
    }
}

internal sealed interface VehicleLocationResult {
    data class Available(val location: VehicleLocation) : VehicleLocationResult
    data class Failed(val reason: String) : VehicleLocationResult
    data object Unsupported : VehicleLocationResult
}
