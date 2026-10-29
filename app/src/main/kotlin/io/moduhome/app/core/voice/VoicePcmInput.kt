package io.moduhome.app.core.voice

/** One request owns signed 16-bit, 16 kHz mono input until capture returns or is cancelled. */
internal interface VoicePcmInput {
    /** True only before opening input or after recording has stopped and its resources were released. */
    val isClosed: Boolean

    suspend fun capture(onStarted: suspend () -> Unit, onFrame: suspend (ByteArray) -> Unit)

    /** Signals capture to finish; the caller still joins capture before trusting isClosed. */
    fun stop()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 3_200
    }
}
