package io.moduhome.app.feature.shared

import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.shared.SharedJourneyProgress
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.JourneyProposal
import io.moduhome.app.core.transfer.DropoffProposal
import io.moduhome.app.core.transfer.JourneyProposalInbox
import io.moduhome.app.core.transfer.ProposalApprovalResult
import io.moduhome.app.core.transfer.ProposalReviewStatus
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Collections

/** An accepted user suggestion, not a verified sender, shop status or routing observation. */
internal data class SharedProposalAcceptance(val proposal: JourneyProposal, val appliedAtMillis: Long, val passengerId: String? = null) {
    init {
        require(appliedAtMillis >= 0)
        require(proposal.unavailableReason(appliedAtMillis) == null)
        require(when (proposal) {
            is ErrandProposal -> passengerId == null
            is DropoffProposal -> !passengerId.isNullOrBlank() && passengerId.length <= 64
        })
    }
}

internal sealed interface SharedProposalReviewResult {
    data class Ready(val review: SharedProposalReview) : SharedProposalReviewResult
    data class Rejected(val reason: String) : SharedProposalReviewResult
}

internal sealed interface SharedProposalApplyResult {
    data class Applied(val session: SharedJourneySession, val inbox: JourneyProposalInbox) : SharedProposalApplyResult
    data class Rejected(val reason: String) : SharedProposalApplyResult
}

/** The app must keep this review intact until the user explicitly accepts its replacement. */
internal class SharedProposalReview private constructor(
    val session: SharedJourneySession,
    val proposal: JourneyProposal,
    val zoneId: ZoneId,
    val reviewedAtMillis: Long,
    val passengerId: String? = null,
    val doorstepConfirmed: Boolean = false,
) {
    val passengerChoices: List<SharedPassengerDraft>
        get() {
            val people = session.draft.passengers
            if (people.size != 1) return people
            val id = if (people.single().id == "passenger-2") "passenger-1" else "passenger-2"
            return people + SharedPassengerDraft(id, "동승자 2")
        }

    fun forPassenger(id: String): SharedProposalReview? =
        if (proposal !is DropoffProposal || passengerChoices.none { it.id == id }) null
        else if (passengerId == id) this
        else SharedProposalReview(session, proposal, zoneId, reviewedAtMillis, id, false)

    fun confirmDoorstep(confirmed: Boolean): SharedProposalReview? =
        if (proposal !is DropoffProposal || passengerId == null) null
        else SharedProposalReview(session, proposal, zoneId, reviewedAtMillis, passengerId, confirmed)

    internal fun approve(
        currentSession: SharedJourneySession,
        inbox: JourneyProposalInbox,
        nowMillis: Long,
        zoneId: ZoneId,
    ): SharedProposalApplyResult {
        if (currentSession != session) return SharedProposalApplyResult.Rejected("귀가 조건이 바뀌었어요. 제안을 다시 확인해주세요")
        if (zoneId != this.zoneId) return SharedProposalApplyResult.Rejected("시간대가 바뀌었어요. 마감을 다시 확인해주세요")
        if (nowMillis < reviewedAtMillis) return SharedProposalApplyResult.Rejected("기기 시각을 확인한 뒤 제안을 다시 열어주세요")
        val entry = inbox.entries.firstOrNull { it.proposal.id == proposal.id }
            ?: return SharedProposalApplyResult.Rejected("검토할 제안을 찾지 못했어요")
        if (entry.proposal != proposal) return SharedProposalApplyResult.Rejected("제안 내용이 바뀌었어요. 최신 내용을 다시 확인해주세요")
        proposalRejectionReason(currentSession, proposal, nowMillis, zoneId)?.let {
            return SharedProposalApplyResult.Rejected(it)
        }
        val draft = when (val value = proposal) {
            is ErrandProposal -> currentSession.draft.copy(errand = value.toSharedErrand())
            is DropoffProposal -> {
                val slot = passengerChoices.firstOrNull { it.id == passengerId }
                    ?: return SharedProposalApplyResult.Rejected("하차할 동승자를 선택해주세요")
                if (!doorstepConfirmed) return SharedProposalApplyResult.Rejected("집 앞에서 바로 내릴 수 있는지 확인해주세요")
                val passenger = slot.chooseDropoff(value.place).copy(
                    name = value.passengerName.takeUnless { it == "동승자" } ?: slot.name,
                    latestArrivalAtMillis = value.deadlineAtMillis,
                    doorstepDropoffConfirmed = true,
                    doorstepConfirmedAtMillis = nowMillis,
                    verification = DropoffVerification(AccessCheck.SUITABLE, AccessCheck.SUITABLE, "운전자가 받은 하차지를 직접 확인", nowMillis),
                )
                val people = if (currentSession.draft.passengers.any { it.id == passenger.id }) {
                    currentSession.draft.passengers.map { if (it.id == passenger.id) passenger else it }
                } else currentSession.draft.passengers + passenger
                if (people.size !in 1..2) return SharedProposalApplyResult.Rejected("동승자는 두 명까지 함께 귀가할 수 있어요")
                currentSession.draft.copy(passengers = people)
            }
        }
        return when (val approval = inbox.approve(proposal.id, proposal.revision, nowMillis)) {
            is ProposalApprovalResult.Rejected -> SharedProposalApplyResult.Rejected(approval.reason)
            is ProposalApprovalResult.Approved -> {
                val retained = currentSession.acceptedProposals.filter {
                    it.proposal.id != proposal.id && it.proposal.expiresAtMillis > nowMillis
                }
                val accepted = retained + SharedProposalAcceptance(proposal, nowMillis, passengerId)
                val next = currentSession.resetToInput(draft).copy(acceptedProposals = immutableCopy(accepted))
                SharedProposalApplyResult.Applied(next, approval.inbox)
            }
        }
    }

    companion object {
        internal fun prepare(
            session: SharedJourneySession,
            inbox: JourneyProposalInbox,
            id: String,
            revision: Int,
            nowMillis: Long,
            zoneId: ZoneId,
        ): SharedProposalReviewResult {
            if (nowMillis < 0) return SharedProposalReviewResult.Rejected("기기 시각을 확인해주세요")
            val entry = inbox.entries.firstOrNull { it.proposal.id == id }
                ?: return SharedProposalReviewResult.Rejected("검토할 제안을 찾지 못했어요")
            if (entry.proposal.revision != revision) return SharedProposalReviewResult.Rejected("제안이 수정됐어요. 최신 내용을 다시 확인해주세요")
            if (entry.status != ProposalReviewStatus.PENDING) return SharedProposalReviewResult.Rejected("이미 검토한 제안이에요")
            proposalRejectionReason(session, entry.proposal, nowMillis, zoneId)?.let {
                return SharedProposalReviewResult.Rejected(it)
            }
            val snapshot = session.copy(
                draft = session.draft.copy(passengers = immutableCopy(session.draft.passengers)),
                progress = session.progress.copy(completedAtMillis = immutableCopy(session.progress.completedAtMillis)),
                acceptedProposals = immutableCopy(session.acceptedProposals),
            )
            return SharedProposalReviewResult.Ready(SharedProposalReview(snapshot, entry.proposal, zoneId, nowMillis))
        }
    }
}

/** History is retained after expiry; expiry only prunes records when another proposal is accepted. */
internal fun validSharedProposalHistory(history: List<SharedProposalAcceptance>): Boolean =
    history.size <= JourneyProposalInbox.MAX_ENTRIES &&
        history.map { it.proposal.id }.distinct().size == history.size

private fun proposalRejectionReason(
    session: SharedJourneySession,
    proposal: JourneyProposal,
    nowMillis: Long,
    zoneId: ZoneId,
): String? {
    if (session.progress.stage != JourneyStage.INPUT || session.progress != SharedJourneyProgress()) {
        return "입력 화면에서 제안을 반영해주세요. 진행 중인 계획은 그대로 유지해요"
    }
    if (!validSharedProposalHistory(session.acceptedProposals) || session.acceptedProposals.any { it.appliedAtMillis > nowMillis }) {
        return "저장된 제안 수락 기록과 기기 시각을 확인해주세요"
    }
    proposal.unavailableReason(nowMillis)?.let { return it }
    if (session.acceptedProposals.any { it.proposal.id == proposal.id && it.proposal.revision >= proposal.revision }) {
        return "이미 같은 버전이나 더 최신 제안을 반영했어요"
    }
    proposal.deadlineAtMillis?.let { deadline ->
        val departureDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val deadlineDate = Instant.ofEpochMilli(deadline).atZone(zoneId).toLocalDate()
        if (ChronoUnit.DAYS.between(departureDate, deadlineDate) !in 0L..1L) {
            return "함께 귀가의 도착·완료 마감은 오늘 또는 내일로 설정해주세요"
        }
    }
    if (proposal is ErrandProposal && session.draft.errand == proposal.toSharedErrand()) return "이미 같은 장소와 조건의 볼일이 있어요"
    val retainedCount = session.acceptedProposals.count {
        it.proposal.id != proposal.id && it.proposal.expiresAtMillis > nowMillis
    }
    if (retainedCount >= JourneyProposalInbox.MAX_ENTRIES) return "수락한 제안이 많아요. 유효시간이 지난 뒤 다시 시도해주세요"
    return null
}

private fun ErrandProposal.toSharedErrand() = SharedErrandDraft(
    kind = kind, place = place, stayMinutes = stayMinutes, walkingMinutesOneWay = walkingMinutesOneWay,
    ready = ready, deadlineAtMillis = deadlineAtMillis,
)

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
