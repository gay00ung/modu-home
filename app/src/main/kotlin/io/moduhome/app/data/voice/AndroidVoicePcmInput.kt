package io.moduhome.app.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.moduhome.app.core.voice.VoicePcmInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** AudioRecord stays on the capture call's IO context, including its stop and release. */
internal class AndroidVoicePcmInput(context: Context) : VoicePcmInput {
    private val context = context.applicationContext
    private val stopping = AtomicBoolean(false)
    private val used = AtomicBoolean(false)
    @Volatile override var isClosed: Boolean = true
        private set

    override fun stop() { stopping.set(true) }

    override suspend fun capture(onStarted: suspend () -> Unit, onFrame: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        check(used.compareAndSet(false, true))
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val minimum = AudioRecord.getMinBufferSize(VoicePcmInput.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(VoicePcmInput.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, VoicePcmInput.FRAME_BYTES * 2))
            .build()
        isClosed = false
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            currentCoroutineContext().ensureActive()
            if (!stopping.get()) {
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                onStarted()
                val frame = ByteArray(VoicePcmInput.FRAME_BYTES)
                var filled = 0
                while (!stopping.get()) {
                    currentCoroutineContext().ensureActive()
                    val count = recorder.read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0 && count % 2 == 0)
                    if (count == 0) delay(10) else {
                        filled += count
                        if (filled == frame.size) {
                            onFrame(frame.copyOf())
                            filled = 0
                        }
                    }
                }
            }
        } finally {
            val stopped = try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED
            } catch (_: Exception) { false }
            val released = try { recorder.release(); true } catch (_: Exception) { false }
            isClosed = stopped && released
        }
        check(isClosed)
    }
}
