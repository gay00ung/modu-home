package io.moduhome.app.feature.shared

import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.shared.SharedJourneyPlan
import io.moduhome.app.core.shared.SharedGuidanceArrival
import java.time.ZoneId

/** The app owns this state, including paging and the vehicle's pending guidance update. */
internal data class SharedVehicleUiState(
    val draft: SharedJourneyDraft,
    val stage: JourneyStage = JourneyStage.INPUT,
    val plans: List<SharedJourneyPlan> = emptyList(),
    val selectedPlanId: String? = null,
    val completedStops: Int = 0,
    val planPage: Int = 0,
    val showErrors: Boolean = false,
    val errors: List<String> = emptyList(),
    val navigationPending: Boolean = false,
    val passedStopCount: Int = 0,
    val terminalStopCompletionAvailable: Boolean = false,
    val estimateLabel: String? = null,
    val directHomeMinutes: Int? = null,
    val pendingProposalCount: Int = 0,
    val guidanceArrival: SharedGuidanceArrival? = null,
    val guidanceNowMillis: Long = 0L,
    val guidanceZoneId: ZoneId = ZoneId.systemDefault(),
) {
    val selectedPlan: SharedJourneyPlan? get() = plans.firstOrNull { it.id == selectedPlanId }
    val canConfirmPassedStop: Boolean get() = passedStopCount in 1..2
}

internal data class SharedVehicleActions(
    val onDraftChange: (SharedJourneyDraft) -> Unit,
    val onPassengerCountChange: (Int) -> Unit,
    val onChooseDriverHome: () -> Unit,
    val onChoosePassengerDropoff: (String) -> Unit,
    val onErrandChoice: (VisitKind?) -> Unit,
    val onChooseErrandPlace: () -> Unit,
    /** The owner records the user's doorstep/access confirmation and its time together. */
    val onConfirmDropoff: (String, Boolean) -> Unit,
    val onCompare: () -> Unit,
    val onSelectPlan: (String) -> Unit,
    val onPlanPageChange: (Int) -> Unit,
    val onReview: () -> Unit,
    val onStart: () -> Unit,
    val onCompleteNextStop: () -> Unit,
    val onConfirmPassedStop: () -> Unit,
    val onRetryNavigation: () -> Unit,
    val onFinish: () -> Unit,
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onNewJourney: () -> Unit,
    val onCancel: () -> Unit,
    val onLeave: () -> Unit,
    val onReconnect: () -> Unit,
    val onOpenProposals: () -> Unit,
    val onOpenPhoneConnection: () -> Unit,
)
