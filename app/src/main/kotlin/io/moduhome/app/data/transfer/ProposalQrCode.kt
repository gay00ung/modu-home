package io.moduhome.app.data.transfer

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Local bitmap boundary. Run encoding and decoding off the UI thread. */
internal object ProposalQrCode {
    const val MAX_IMAGE_SIDE = ProposalQrPayload.MAX_IMAGE_SIDE
    const val MAX_IMAGE_PIXELS = ProposalQrPayload.MAX_IMAGE_PIXELS

    fun encode(link: String): Bitmap? {
        val matrix = ProposalQrPayload.encode(link) ?: return null
        return Bitmap.createBitmap(
            ProposalQrPayload.pixels(matrix), matrix.width, matrix.height, Bitmap.Config.ARGB_8888,
        )
    }

    /** The caller must downsample a selected image before decoding it into a Bitmap. */
    fun decode(bitmap: Bitmap): String? {
        if (bitmap.isRecycled || !ProposalQrPayload.acceptsImage(bitmap.width, bitmap.height)) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        return try {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ProposalQrPayload.decode(pixels, bitmap.width, bitmap.height)
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** ZXing-only codec so capacity, Unicode and image recognition can be tested on the JVM. */
internal object ProposalQrPayload {
    const val IMAGE_SIDE = 768
    const val MAX_IMAGE_SIDE = 2_048
    const val MAX_IMAGE_PIXELS = MAX_IMAGE_SIDE * MAX_IMAGE_SIDE
    private const val BLACK = -0x1000000
    private const val WHITE = -1

    fun encode(link: String): BitMatrix? {
        if (link.isBlank() || link.length > ProposalLinkCodec.MAX_LINK_LENGTH) return null
        return try {
            QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, IMAGE_SIDE, IMAGE_SIDE, mapOf(
                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
            ))
        } catch (_: WriterException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun pixels(matrix: BitMatrix): IntArray {
        require(acceptsImage(matrix.width, matrix.height))
        return IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) BLACK else WHITE
        }
    }

    fun acceptsImage(width: Int, height: Int): Boolean =
        width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE &&
            width.toLong() * height <= MAX_IMAGE_PIXELS

    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        if (!acceptsImage(width, height) || pixels.size != width * height) return null
        val opaque = if (pixels.any { it ushr 24 != 255 }) {
            IntArray(pixels.size) { index -> compositeOnWhite(pixels[index]) }
        } else pixels
        val source = RGBLuminanceSource(width, height, opaque)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name())
        for (candidate in listOf(source, source.invert())) {
            try {
                val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
                if (text.isNotBlank() && text.length <= ProposalLinkCodec.MAX_LINK_LENGTH) return text
            } catch (_: ReaderException) {
                // A QR can be light on dark. Try the inverse after a normal decode fails.
            }
        }
        return null
    }

    private fun compositeOnWhite(color: Int): Int {
        val alpha = color ushr 24
        if (alpha == 255) return color
        if (alpha == 0) return WHITE
        fun channel(shift: Int): Int = (((color ushr shift) and 255) * alpha + 255 * (255 - alpha) + 127) / 255
        return BLACK or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
