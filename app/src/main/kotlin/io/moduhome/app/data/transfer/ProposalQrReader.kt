package io.moduhome.app.data.transfer

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun readProposalQr(resolver: ContentResolver, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val scale = minOf(1.0, ProposalQrCode.MAX_IMAGE_SIDE.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try { ProposalQrCode.decode(bitmap) } finally { bitmap.recycle() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
