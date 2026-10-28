package io.moduhome.app.core.navigation

/** Approval of an observed route, not permission to overwrite any subsequently changed route. */
internal class RouteChangeApproval private constructor(
    val snapshot: NavigationSnapshot,
    val routeRevision: Long,
) {
    fun matches(currentSnapshot: NavigationSnapshot?, currentRevision: Long): Boolean =
        currentRevision == routeRevision && currentSnapshot == snapshot

    companion object {
        fun fromObservation(
            snapshot: NavigationSnapshot?,
            revisionBefore: Long,
            revisionAfter: Long,
        ): RouteChangeApproval? {
            if (snapshot == null || revisionBefore < 0 || revisionBefore != revisionAfter) return null
            // Own the list rather than retain a caller's mutable list across a confirmation dialog.
            return RouteChangeApproval(snapshot.copy(waypoints = snapshot.waypoints.toList()), revisionAfter)
        }
    }
}

/** Completed visits are an ordered prefix; the final home destination never changes. */
internal fun NavigationPlan.remainingAfter(completedVisits: Int): NavigationPlan {
    require(completedVisits in 0..waypoints.size)
    return copy(waypoints = waypoints.drop(completedVisits))
}
