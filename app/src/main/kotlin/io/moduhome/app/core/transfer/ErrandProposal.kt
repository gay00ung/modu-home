package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind

/** A suggestion to review. Receiving this value does not approve or start a route. */
internal data class ErrandProposal(
    override val id: String,
    override val revision: Int,
    override val createdAtMillis: Long,
    override val expiresAtMillis: Long,
    val kind: VisitKind,
    override val place: NavigationPoint,
    val stayMinutes: Int,
    val walkingMinutesOneWay: Int = 0,
    val ready: Boolean = kind != VisitKind.PICKUP,
    override val deadlineAtMillis: Long? = null,
) : JourneyProposal {
    init {
        requireProposalFields(this)
        require(stayMinutes in 0..180 && walkingMinutesOneWay in 0..30)
    }

    companion object {
        const val MAX_LIFETIME_MILLIS: Long = JourneyProposal.MAX_LIFETIME_MILLIS
    }
}
