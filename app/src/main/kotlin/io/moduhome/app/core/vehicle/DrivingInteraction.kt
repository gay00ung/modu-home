package io.moduhome.app.core.vehicle

import kotlinx.coroutines.flow.StateFlow

/** UI permissions for the current display, without claiming the vehicle is parked or moving. */
internal sealed interface DrivingInteraction {
    data object NotAutomotive : DrivingInteraction
    data class Unknown(val reason: String = "차량의 입력 제한 상태를 확인하고 있어요") : DrivingInteraction
    data class Known(
        val distractionOptimizationRequired: Boolean,
        val keyboardRestricted: Boolean = false,
        val setupRestricted: Boolean = false,
        val visibleVoiceTranscriptionRestricted: Boolean = false,
        val maximumTextLength: Int? = null,
        val maximumContentItems: Int? = null,
        val maximumContentDepth: Int? = null,
    ) : DrivingInteraction {
        init {
            require(maximumTextLength == null || maximumTextLength >= 0)
            require(maximumContentItems == null || maximumContentItems >= 0)
            require(maximumContentDepth == null || maximumContentDepth >= 0)
        }
    }

    /** Forms and setup stay unavailable while display restrictions are unknown or active. */
    val editingAllowed: Boolean
        get() = when (this) {
            NotAutomotive -> true
            is Unknown -> false
            is Known -> !distractionOptimizationRequired && !keyboardRestricted && !setupRestricted
        }

    /** This controls visible recognized words, not permission to record audio or execute a command. */
    val voiceTranscriptionAllowed: Boolean
        get() = when (this) {
            NotAutomotive -> true
            is Unknown -> false
            is Known -> !visibleVoiceTranscriptionRestricted
        }
}

internal interface DrivingRestrictions {
    val state: StateFlow<DrivingInteraction>
    fun connect()
    fun close()
}
