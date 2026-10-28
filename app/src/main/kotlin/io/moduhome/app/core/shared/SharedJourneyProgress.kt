package io.moduhome.app.core.shared

import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.remainingAfter
import io.moduhome.app.core.planning.JourneyStage

/** The full shared plan stays intact; only the next two stops fit in the vehicle route. */
internal fun SharedJourneyPlan.currentWindow(home: NavigationPoint, completedCount: Int): NavigationPlan {
    require(stops.size <= 3)
    require(completedCount in 0..stops.size)
    return NavigationPlan(home, stops.drop(completedCount).take(2).map { it.place.point })
}

/**
 * Local transition state. A session restore factory must validate stage, selected full plan,
 * and windows together before accepting persisted values. Always pass the selected plan from
 * that validated session; the plan ID alone is not a fingerprint of all stop coordinates.
 */
internal data class SharedJourneyProgress(
    val stage: JourneyStage = JourneyStage.INPUT,
    val selectedPlanId: String? = null,
    val completedStopCount: Int = 0,
    val completedAtMillis: List<Long> = emptyList(),
    val startedAtMillis: Long? = null,
    val confirmedWindow: NavigationPlan? = null,
    val pendingWindow: NavigationPlan? = null,
) {
    init {
        require(completedStopCount in 0..3)
        require(completedAtMillis.size == completedStopCount)
        require(startedAtMillis == null || startedAtMillis >= 0)
        require(completedAtMillis.all { it >= 0 && (startedAtMillis == null || it >= startedAtMillis) })
        require(completedAtMillis.zipWithNext().all { (first, next) -> first <= next })
    }

    val navigationPending: Boolean get() = stage == JourneyStage.ACTIVE && pendingWindow != null

    fun compare(plans: List<SharedJourneyPlan>): SharedJourneyProgress =
        if (stage == JourneyStage.INPUT && plans.isNotEmpty()) {
            copy(stage = JourneyStage.COMPARE, selectedPlanId = plans.first().id)
        } else this

    fun select(id: String, plans: List<SharedJourneyPlan>): SharedJourneyProgress =
        if (stage == JourneyStage.COMPARE && plans.any { it.id == id }) copy(selectedPlanId = id) else this

    fun review(plans: List<SharedJourneyPlan>): SharedJourneyProgress =
        if (stage == JourneyStage.COMPARE && plans.any { it.id == selectedPlanId && it.isValid }) {
            copy(stage = JourneyStage.REVIEW)
        } else this

    /** Starting records intent; only a readback can establish that the vehicle accepted it. */
    fun start(plan: SharedJourneyPlan, home: NavigationPoint, now: Long): SharedJourneyProgress {
        require(now >= 0)
        return if (stage == JourneyStage.REVIEW && selectedPlanId == plan.id && plan.isValid) {
            copy(stage = JourneyStage.ACTIVE, completedStopCount = 0, completedAtMillis = emptyList(),
                startedAtMillis = now, confirmedWindow = null, pendingWindow = plan.currentWindow(home, 0))
        } else this
    }

    /** A physical stop completion survives a subsequent SDK failure. Never call start to retry. */
    fun confirmStop(plan: SharedJourneyPlan, now: Long): SharedJourneyProgress {
        if (stage != JourneyStage.ACTIVE || !matches(plan) || navigationPending ||
            completedStopCount >= plan.stops.size) return this
        val home = confirmedWindow?.destination ?: return this
        require(now >= (completedAtMillis.lastOrNull() ?: startedAtMillis ?: 0))
        val completed = completedStopCount + 1
        return copy(completedStopCount = completed, completedAtMillis = completedAtMillis + now,
            pendingWindow = plan.currentWindow(home, completed))
    }

    /**
     * Only call after the user explicitly confirms that the first pending stop was completed.
     * A shorter native route alone never proves a passenger got out or an errand was done.
     */
    fun confirmPassedStop(
        plan: SharedJourneyPlan,
        snapshot: NavigationSnapshot,
        now: Long,
    ): SharedJourneyProgress = confirmPassedStops(plan, snapshot, count = 1, now = now)

    /**
     * Records only the pending prefix explicitly confirmed completed by the user.
     * The caller must bind that approval to this progress, count and a stable vehicle readback.
     * An active route missing exactly that prefix is evidence of the remaining guidance,
     * never evidence that the physical stops were completed without the user's approval.
     */
    fun confirmPassedStops(
        plan: SharedJourneyPlan,
        snapshot: NavigationSnapshot,
        count: Int,
        now: Long,
    ): SharedJourneyProgress {
        if (count !in 1..2) return this
        if (stage != JourneyStage.ACTIVE || !matches(plan) || completedStopCount >= plan.stops.size) return this
        val pending = pendingWindow ?: return this
        if (count > pending.waypoints.size || count > plan.stops.size - completedStopCount) return this
        val observedRemainder = pending.remainingAfter(count)
        if (!snapshot.confirms(observedRemainder)) return this
        require(now >= (completedAtMillis.lastOrNull() ?: startedAtMillis ?: 0))
        val completed = completedStopCount + count
        return copy(
            completedStopCount = completed,
            completedAtMillis = completedAtMillis + List(count) { now },
            confirmedWindow = observedRemainder,
            pendingWindow = plan.currentWindow(pending.destination, completed),
        )
    }

    /**
     * Reads only resolve an existing pending window; they never complete physical stops.
     * With no pending window this is deliberately a no-op, even for an external route change.
     * The observing flow must detect that mismatch and call requestNavigation to mark the
     * selected window pending. That still does not authorize sending it to the vehicle.
     */
    fun observeNavigation(snapshot: NavigationSnapshot?): SharedJourneyProgress {
        if (stage != JourneyStage.ACTIVE) return this
        val pending = pendingWindow ?: return this
        return if (snapshot?.confirms(pending) == true) {
            copy(confirmedWindow = pending, pendingWindow = null)
        } else this
    }

    /**
     * Preparing a retry changes no completion records and authorizes no automatic transmission.
     * confirmedWindow retains historical readback while pendingWindow is the required target;
     * it must not be presented as proof that the vehicle currently follows that old route.
     */
    fun requestNavigation(plan: SharedJourneyPlan, home: NavigationPoint): SharedJourneyProgress =
        if (stage == JourneyStage.ACTIVE && matches(plan) && keepsHome(home)) {
            copy(pendingWindow = plan.currentWindow(home, completedStopCount))
        } else this

    /** A persisted old confirmation is not proof of the vehicle's route after process restart. */
    fun afterRestart(plan: SharedJourneyPlan, home: NavigationPoint): SharedJourneyProgress =
        if (stage == JourneyStage.ACTIVE && matches(plan) && keepsHome(home)) {
            copy(confirmedWindow = null, pendingWindow = plan.currentWindow(home, completedStopCount))
        } else this

    /**
     * Explicit user confirmation of arrival home, not an assertion that navigation is active.
     * Native guidance may already have ended normally at home, including after a restart.
     * Every intermediate stop still needs its own recorded completion first.
     */
    fun finish(plan: SharedJourneyPlan, now: Long): SharedJourneyProgress {
        if (stage != JourneyStage.ACTIVE || !matches(plan) || completedStopCount != plan.stops.size) return this
        val started = startedAtMillis ?: return this
        require(now >= (completedAtMillis.lastOrNull() ?: started))
        // Retain the actual observation history instead of inventing a vehicle confirmation.
        // In COMPLETE, a retained pending home window is history, never work to send.
        return copy(stage = JourneyStage.COMPLETE)
    }

    private fun matches(plan: SharedJourneyPlan): Boolean {
        if (selectedPlanId != plan.id || !plan.isValid || plan.stops.size > 3 || completedStopCount > plan.stops.size) {
            return false
        }
        val window = pendingWindow ?: confirmedWindow ?: return false
        return window == plan.currentWindow(window.destination, completedStopCount)
    }

    private fun keepsHome(home: NavigationPoint): Boolean =
        (pendingWindow ?: confirmedWindow)?.destination?.let { it == home } ?: true
}
