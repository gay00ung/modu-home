package io.moduhome.app.data.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalLinkCodecTest {
    private val now = 1_800_000_000_000L
    private val proposal = ErrandProposal(
        id = "proposal-001", revision = 3, createdAtMillis = now, expiresAtMillis = now + 3_600_000,
        kind = VisitKind.PICKUP,
        place = NavigationPoint("빵 & 커피 + 2층 ☕", 37.5, 127.0, "서울 매장? A=2 #입구", "poi/123", "0", "search"),
        stayMinutes = 5, walkingMinutesOneWay = 2, ready = true, deadlineAtMillis = now + 1_800_000,
    )
    private val link get() = ProposalLinkCodec.encode(proposal)
    private fun assertInvalid(value: String, atMillis: Long = now) {
        assertTrue(ProposalLinkCodec.decode(value, atMillis) is ProposalDecodeResult.Invalid)
    }

    @Test fun unicodeAndReservedCharactersRoundTripWithoutLosingPlaceIdentityOrConditions() {
        val result = ProposalLinkCodec.decode(link, now) as ProposalDecodeResult.Valid
        assertEquals(proposal, result.proposal)
        assertTrue(link.all { it.code in 0x21..0x7e })
    }

    @Test fun aSuggestionWithoutDeadlinePreservesTheAbsence() {
        val noDeadline = proposal.copy(kind = VisitKind.GROCERIES, deadlineAtMillis = null, ready = false)
        assertEquals(ProposalDecodeResult.Valid(noDeadline), ProposalLinkCodec.decode(ProposalLinkCodec.encode(noDeadline), now))
    }

    @Test fun unsupportedVersionDoesNotAttemptToInterpretThePayload() {
        assertInvalid(link.replace("v=1&", "v=2&"))
    }

    @Test fun expiredLinksAreRejectedAtTheExactExpirationBoundary() {
        assertInvalid(link, proposal.expiresAtMillis)
    }

    @Test fun malformedAndAmbiguousLinksAreRejected() {
        listOf(
            link.replace("modu-home://", "https://"),
            link.replace("//proposal?", "//other?"),
            link.replace("//proposal?", "//proposal/path?"),
            link.replace("//proposal?", "//sender@proposal?"),
            link.replace("//proposal?", "//proposal:123?"),
            "$link#fragment",
            "$link&extra=value",
            "$link&id=proposal-other",
            link.replace("&r=3", ""),
            link.replace("&r=3", "&id=proposal-other"),
            link.replace("ready=1", "ready=true"),
            link.replace("kind=PICKUP", "kind=UNKNOWN"),
            link.replace("&stay=5", "&stay=99999999999999999999"),
            link.replace("lat=37.5", "lat=NaN"),
            link.replace("lon=127.0", "lon=Infinity"),
            link.replace("&stay=5", "&stay=-1"),
            link.replace("&walk=2", "&walk=31"),
            link.replace("&name=", "&name=%00"),
            link.replace("&name=", "&name=%FF"),
            link.replace("&name=", "&name=%C0%AF"),
            link.replace("&name=", "&name=%"),
        ).forEach { assertInvalid(it) }
    }

    @Test fun namesAndAddressesCannotBeUsedToCarryOversizedPayloads() {
        assertInvalid(link.replace(Regex("name=[^&]*"), "name=" + "a".repeat(121)))
        assertInvalid(link.replace(Regex("address=[^&]*"), "address=" + "a".repeat(301)))
        assertInvalid("modu-home://proposal?" + "a".repeat(ProposalLinkCodec.MAX_LINK_LENGTH))
    }

    @Test fun aLinkCannotRemainValidForMoreThanOneDay() {
        assertInvalid(link.replace("expires=${proposal.expiresAtMillis}", "expires=${now + ErrandProposal.MAX_LIFETIME_MILLIS + 1}"))
    }

    @Test fun futureCreationAndPastDeadlinesAreNotAcceptedAsFreshSuggestions() {
        assertInvalid(link.replace("created=$now", "created=${now + 300_001}"))
        assertInvalid(link, checkNotNull(proposal.deadlineAtMillis) + 1)
    }
}
