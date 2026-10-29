package io.moduhome.app.core.voice

import kotlinx.coroutines.flow.StateFlow

/** Recognition starts only when the caller invokes recognize from a user action. */
internal interface VoiceInputGateway {
    val state: StateFlow<VoiceInputState>
    suspend fun recognize(): VoiceRecognitionResult
    fun stop()
    fun close()
}

internal sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data object Preparing : VoiceInputState
    data class Listening(val partialText: String = "") : VoiceInputState
    data object Processing : VoiceInputState
    data object Stopping : VoiceInputState
    data class Failed(val reason: String) : VoiceInputState
}

internal sealed interface VoiceRecognitionResult {
    /** The caller must parse and ask for confirmation before changing a plan. */
    data class Recognized(val text: String) : VoiceRecognitionResult
    data class Unavailable(val reason: String) : VoiceRecognitionResult
    data object NoSpeech : VoiceRecognitionResult
    data object Stopped : VoiceRecognitionResult
    data object Busy : VoiceRecognitionResult
}
