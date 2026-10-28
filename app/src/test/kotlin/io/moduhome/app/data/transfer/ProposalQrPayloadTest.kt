package io.moduhome.app.data.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.ErrandProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalQrPayloadTest {
    private val now = 1_800_000_000_000L
    private val proposal = ErrandProposal(
        id = "qr-proposal-001", revision = 1, createdAtMillis = now, expiresAtMillis = now + 3_600_000,
        kind = VisitKind.PICKUP,
        place = NavigationPoint("빵과 커피 픽업 & 지하 1층", 37.5, 127.0,
            "서울특별시 " + "매장 출입구 안내 ".repeat(8), "poi-example", "0", "search"),
        stayMinutes = 5, walkingMinutesOneWay = 2, ready = true,
    )

    @Test fun aLongEncodedKoreanProposalSurvivesRealQrEncodingAndImageDecoding() {
        val link = ProposalLinkCodec.encode(proposal)
        assertTrue(link.length > 800)
        val qr = checkNotNull(ProposalQrPayload.encode(link))
        val decoded = ProposalQrPayload.decode(ProposalQrPayload.pixels(qr), qr.width, qr.height)
        assertEquals(link, decoded)
        assertEquals(ProposalDecodeResult.Valid(proposal), ProposalLinkCodec.decode(checkNotNull(decoded), now))
    }

    @Test fun exportedQrHasAWhiteQuietZoneAndOnlyOpaqueBlackOrWhitePixels() {
        val qr = checkNotNull(ProposalQrPayload.encode(ProposalLinkCodec.encode(proposal)))
        val pixels = ProposalQrPayload.pixels(qr)
        assertTrue(pixels.all { it == -1 || it == -0x1000000 })
        for (index in 0 until qr.width) {
            assertFalse(qr[index, 0])
            assertFalse(qr[index, qr.height - 1])
            assertFalse(qr[0, index])
            assertFalse(qr[qr.width - 1, index])
        }
    }

    @Test fun oversizeLinkIsRejectedWhenItExceedsQrCapacityEvenWithinTheTransferLimit() {
        assertNull(ProposalQrPayload.encode("modu-home://proposal?name=" + "a".repeat(7_000)))
        assertNull(ProposalQrPayload.encode("a".repeat(ProposalLinkCodec.MAX_LINK_LENGTH + 1)))
        assertNull(ProposalQrPayload.encode(" "))
    }

    @Test fun aLightQrOnDarkBackgroundCanStillBeRead() {
        val link = ProposalLinkCodec.encode(proposal)
        val qr = checkNotNull(ProposalQrPayload.encode(link))
        val inverse = ProposalQrPayload.pixels(qr).map { if (it == -1) -0x1000000 else -1 }.toIntArray()
        assertEquals(link, ProposalQrPayload.decode(inverse, qr.width, qr.height))
    }

    @Test fun transparentWhiteBackgroundIsCompositedWithoutMakingTheQrSolidBlack() {
        val link = ProposalLinkCodec.encode(proposal)
        val qr = checkNotNull(ProposalQrPayload.encode(link))
        val transparent = ProposalQrPayload.pixels(qr).map { if (it == -1) 0 else it }.toIntArray()
        assertEquals(link, ProposalQrPayload.decode(transparent, qr.width, qr.height))
    }

    @Test fun unrelatedQrTextIsReturnedForTheProposalCodecToReject() {
        val qr = checkNotNull(ProposalQrPayload.encode("https://example.invalid/"))
        val decoded = ProposalQrPayload.decode(ProposalQrPayload.pixels(qr), qr.width, qr.height)
        assertNotNull(decoded)
        assertTrue(ProposalLinkCodec.decode(checkNotNull(decoded), now) is ProposalDecodeResult.Invalid)
    }

    @Test fun invalidImageSizesAndPixelBuffersAreRejectedBeforeAllocation() {
        assertNull(ProposalQrPayload.decode(intArrayOf(), 0, 0))
        assertNull(ProposalQrPayload.decode(intArrayOf(), Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(ProposalQrPayload.decode(IntArray(100), 9, 9))
        assertFalse(ProposalQrPayload.acceptsImage(ProposalQrPayload.MAX_IMAGE_SIDE + 1, 1))
        assertTrue(ProposalQrPayload.acceptsImage(ProposalQrPayload.MAX_IMAGE_SIDE, ProposalQrPayload.MAX_IMAGE_SIDE))
    }

    @Test fun anOrdinaryWhiteImageDoesNotProduceAQrLink() {
        assertNull(ProposalQrPayload.decode(IntArray(256 * 256) { -1 }, 256, 256))
    }
}
