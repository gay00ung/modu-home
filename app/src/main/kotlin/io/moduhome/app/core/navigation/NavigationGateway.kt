package io.moduhome.app.core.navigation

/** An acknowledgement is not confirmation that navigation has started. */
internal interface NavigationGateway {
    suspend fun requestRoute(plan: NavigationPlan): RouteRequestResult
}

internal data class NavigationPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(name.isNotBlank())
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
    }
}

internal data class NavigationPlan(
    val destination: NavigationPoint,
    val waypoints: List<NavigationPoint> = emptyList(),
)

internal sealed interface RouteRequestResult {
    data object Requested : RouteRequestResult
    data object Unavailable : RouteRequestResult
    data class Rejected(val reason: String) : RouteRequestResult
}
