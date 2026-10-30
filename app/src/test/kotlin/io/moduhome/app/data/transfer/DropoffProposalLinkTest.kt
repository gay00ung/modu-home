package io.moduhome.app.data.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.*
import org.junit.Assert.*
import org.junit.Test

class DropoffProposalLinkTest {
    private val now = 1_800_000_000_000L
    private val proposal = DropoffProposal("dropoff-001", 1, now, now + 3_600_000,
        NavigationPoint("하차 & 입구", 37.5, 127.0, "서울 주소 + 2번"), "친구 A", now + 1_800_000)

    @Test fun dropoffCarriesOnlyItsOwnConditionsAndPreservesUnicode() {
        val link = ProposalLinkCodec.encode(proposal)
        assertEquals(ProposalDecodeResult.Valid(proposal), ProposalLinkCodec.decode(link, now))
        assertFalse(link.contains("stay="))
        assertFalse(link.contains("ready="))
        assertTrue(link.contains("v=2&"))
        assertFalse(proposal.toString().contains(proposal.place.name))
    }

    @Test fun legacyErrandLinksKeepTheExactEncodingAndDecodeAfterUpgrade() {
        val legacy = ErrandProposal("legacy-001", 1, now, now + 3_600_000, VisitKind.GROCERIES,
            NavigationPoint("shop", 37.5, 127.0), 5, ready = true)
        val old = "modu-home://proposal?v=1&id=legacy-001&r=1&created=$now&expires=${now + 3_600_000}" +
            "&kind=GROCERIES&name=shop&lat=37.5&lon=127.0&address=&placeId=&subId=&provider=&stay=5&walk=0&ready=1&deadline="
        assertEquals(old, ProposalLinkCodec.encode(legacy))
        assertEquals(ProposalDecodeResult.Valid(legacy), ProposalLinkCodec.decode(old, now))
    }

    @Test fun mixedKindsDuplicateFieldsMalformedNamesAndExpiredArrivalsAreRejected() {
        val link = ProposalLinkCodec.encode(proposal)
        listOf(link.replace("kind=DROPOFF", "kind=PICKUP"), link.replace("v=2", "v=1"),
            "$link&stay=5", "$link&arrival=$now", link.replace("&passenger=", "&passenger=%00"),
            link.replace("&passenger=", "&passenger=%ED%A0%80"), link.replace("lat=37.5", "lat=NaN"),
            link.replace("arrival=${proposal.deadlineAtMillis}", "arrival=${now - 1}"))
            .forEach { assertTrue(it, ProposalLinkCodec.decode(it, now) is ProposalDecodeResult.Invalid) }
        assertTrue(ProposalLinkCodec.decode(link, proposal.expiresAtMillis) is ProposalDecodeResult.Invalid)
    }

    @Test fun receiptWaitsForDurableStorageAndRedeliveryDoesNotCreateAnotherEntry() {
        val message = RelayMessage("00000000-0000-4000-8000-000000000001", ProposalLinkCodec.encode(proposal))
        val empty = JourneyProposalInbox.empty()
        val failed = receiveRelayProposal(empty, message, now) { false }
        assertEquals(empty, failed.inbox)
        assertNull(failed.acknowledgement)
        val stored = receiveRelayProposal(empty, message, now) { true }
        assertEquals(RelayDeliveryStatus.STORED, stored.acknowledgement)
        assertEquals(ProposalReviewStatus.PENDING, stored.inbox.entries.single().status)
        val repeated = receiveRelayProposal(stored.inbox, message, now + 1) { true }
        assertEquals(stored.inbox, repeated.inbox)
        assertFalse(repeated.newProposal)
    }

    @Test fun aHigherRevisionCannotSwitchAnExistingProposalToAnErrand() {
        val inbox = (JourneyProposalInbox.empty().receive(proposal, now) as ProposalReceiveResult.Pending).inbox
        val changed = ErrandProposal(proposal.id, 2, now, now + 3_600_000, VisitKind.GROCERIES, proposal.place, 5)
        assertTrue(inbox.receive(changed, now) is ProposalReceiveResult.Rejected)
        assertEquals(proposal, inbox.entries.single().proposal)
    }
}
