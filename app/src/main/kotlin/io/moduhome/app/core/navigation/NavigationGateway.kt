package io.moduhome.app.core.navigation

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal interface NavigationGateway {
    val connection: StateFlow<NavigationConnection>
    fun connect()
    fun close()
    suspend fun requestRoute(plan: NavigationPlan): RouteRequestResult
    suspend fun currentRoute(): NavigationSnapshot?
    suspend fun loadPlaces(): PlaceCatalogResult
    suspend fun estimateTrip(destination: NavigationPoint): TripEstimateResult
    suspend fun currentLocation(): VehicleLocationResult
}

internal data class NavigationPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val placeId: String = "",
    val subId: String = "",
    val provider: String = "",
) {
    init {
        require(name.isNotBlank())
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
    }

}

/** Readback may identify a POI without supplying a coordinate. */
internal data class ObservedNavigationPlace(
    val name: String,
    val placeId: String = "",
    val provider: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val subId: String = "",
) {
    init {
        require(name.isNotBlank())
        require((latitude == null) == (longitude == null))
        require(latitude == null || latitude in -90.0..90.0)
        require(longitude == null || longitude in -180.0..180.0)
    }

    fun matches(expected: NavigationPoint): Boolean {
        if (provider.isNotEmpty() && expected.provider.isNotEmpty() && provider != expected.provider) return false
        if (placeId.isNotEmpty() && expected.placeId.isNotEmpty()) {
            return placeId == expected.placeId &&
                (subId.isEmpty() || expected.subId.isEmpty() || subId == expected.subId)
        }
        val actualLatitude = latitude ?: return false
        val actualLongitude = longitude ?: return false
        val lat = Math.toRadians(expected.latitude - actualLatitude)
        val lon = Math.toRadians(expected.longitude - actualLongitude)
        val a = sin(lat / 2) * sin(lat / 2) + cos(Math.toRadians(actualLatitude)) *
            cos(Math.toRadians(expected.latitude)) * sin(lon / 2) * sin(lon / 2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0))) <= 25.0
    }
}

internal data class NavigationPlan(
    val destination: NavigationPoint,
    val waypoints: List<NavigationPoint> = emptyList(),
) {
    init { require(waypoints.size <= 2) }
}

internal data class NavigationSnapshot(
    val destination: ObservedNavigationPlace?,
    val waypoints: List<ObservedNavigationPlace>,
    val guidanceActive: Boolean,
) {
    fun confirms(plan: NavigationPlan): Boolean = guidanceActive &&
        destination?.matches(plan.destination) == true &&
        waypoints.size == plan.waypoints.size &&
        waypoints.zip(plan.waypoints).all { (actual, expected) -> actual.matches(expected) }
}

internal sealed interface NavigationConnection {
    data object Unsupported : NavigationConnection
    data object Connecting : NavigationConnection
    data object Ready : NavigationConnection
    data class Failed(val reason: String) : NavigationConnection
}

internal sealed interface RouteRequestResult {
    /** Destination, ordered waypoints and active guidance were read back from the vehicle. */
    data class Confirmed(val snapshot: NavigationSnapshot) : RouteRequestResult
    data object Unavailable : RouteRequestResult
    data class Rejected(val reason: String, val snapshot: NavigationSnapshot? = null) : RouteRequestResult
}

internal sealed interface PlaceCatalogResult {
    data class Loaded(val bookmarks: List<NavigationPoint>, val recent: List<NavigationPoint>) : PlaceCatalogResult
    data class Failed(val reason: String) : PlaceCatalogResult
}
