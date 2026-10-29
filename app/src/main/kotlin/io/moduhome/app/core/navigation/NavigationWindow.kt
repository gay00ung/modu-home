package io.moduhome.app.core.navigation

/**
 * Coordinate-only places end a window so their coordinates can be read back as a destination.
 * Intermediate places require a POI identity because waypoint coordinates can be unavailable.
 * The complete itinerary and its final home remain owned by the caller.
 */
internal fun verifiableNavigationWindow(home: NavigationPoint, remainingStops: List<NavigationPoint>): NavigationPlan {
    val candidates = remainingStops.take(3)
    val addressIndex = candidates.indexOfFirst { it.placeId.isBlank() }
    return if (addressIndex >= 0) {
        NavigationPlan(candidates[addressIndex], candidates.take(addressIndex))
    } else {
        NavigationPlan(home, candidates.take(2))
    }
}
