package io.moduhome.app.data.transfer

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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

    @Test fun aSmallQrInsideDecorativeFinderPatternsStillDecodes() {
        val link = "https://example.invalid/qr?value=" + "abcdef".repeat(80)
        val qr = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 256, 256, mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4,
        ))
        val width = 2_048
        val pixels = IntArray(width * 1_152) { -1 }
        draw(qr, pixels, width, 1_150, 200)
        for ((left, top) in listOf(100 to 100, 900 to 100, 100 to 900)) {
            for (y in 0 until 28) for (x in 0 until 28) {
                val u = x / 4
                val v = y / 4
                val black = u == 0 || u == 6 || v == 0 || v == 6 || (u in 2..4 && v in 2..4)
                pixels[(top + y) * width + left + x] = if (black) -0x1000000 else -1
            }
        }
        assertEquals(link, ProposalQrPayload.decode(pixels, width, 1_152))
    }

    @Test fun differentQrPayloadsInOneImageRequireTheUserToChooseAnotherImage() {
        val first = checkNotNull(ProposalQrPayload.encode("https://example.invalid/first"))
        val second = checkNotNull(ProposalQrPayload.encode("https://example.invalid/second"))
        val pixels = IntArray(1_600 * 800) { -1 }
        draw(first, pixels, 1_600, 16, 16)
        draw(second, pixels, 1_600, 816, 16)
        assertNull(ProposalQrPayload.decode(pixels, 1_600, 800))
    }

    @Test fun duplicateCopiesOfTheSameQrRemainUnambiguous() {
        val link = "https://example.invalid/same"
        val qr = checkNotNull(ProposalQrPayload.encode(link))
        val pixels = IntArray(1_600 * 800) { -1 }
        draw(qr, pixels, 1_600, 16, 16)
        draw(qr, pixels, 1_600, 816, 16)
        assertEquals(link, ProposalQrPayload.decode(pixels, 1_600, 800))
    }

    private fun draw(qr: BitMatrix, pixels: IntArray, width: Int, left: Int, top: Int) {
        for (y in 0 until qr.height) for (x in 0 until qr.width) {
            pixels[(top + y) * width + left + x] = if (qr[x, y]) -0x1000000 else -1
        }
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
