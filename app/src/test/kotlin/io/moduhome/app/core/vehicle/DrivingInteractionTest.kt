package io.moduhome.app.core.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingInteractionTest {
    @Test fun anUnknownVehicleStateDoesNotEnableFormsOrVisibleDictation() {
        val state = DrivingInteraction.Unknown()
        assertFalse(state.editingAllowed)
        assertFalse(state.voiceTranscriptionAllowed)
    }

    @Test fun aPhoneDoesNotAcquireVehicleOnlyEditingRestrictions() {
        assertTrue(DrivingInteraction.NotAutomotive.editingAllowed)
        assertTrue(DrivingInteraction.NotAutomotive.voiceTranscriptionAllowed)
    }

    @Test fun anExplicitUnrestrictedDisplayAllowsEditing() {
        val state = DrivingInteraction.Known(distractionOptimizationRequired = false)
        assertTrue(state.editingAllowed)
        assertTrue(state.voiceTranscriptionAllowed)
    }

    @Test fun theBaselineDrivingRequirementAlreadyPreventsForms() {
        val state = DrivingInteraction.Known(distractionOptimizationRequired = true)
        assertFalse(state.editingAllowed)
        assertTrue(state.voiceTranscriptionAllowed)
    }

    @Test fun keyboardAndSetupFlagsCannotAccidentallyEnableEditing() {
        assertFalse(DrivingInteraction.Known(false, keyboardRestricted = true).editingAllowed)
        assertFalse(DrivingInteraction.Known(false, setupRestricted = true).editingAllowed)
    }

    @Test fun visibleVoiceTranscriptionIsASeparateRestriction() {
        val state = DrivingInteraction.Known(false, visibleVoiceTranscriptionRestricted = true)
        assertTrue(state.editingAllowed)
        assertFalse(state.voiceTranscriptionAllowed)
    }

    @Test fun aFreshStateDoesNotRetainThePreviousDisplaysRestrictions() {
        val restricted = DrivingInteraction.Known(true, keyboardRestricted = true, setupRestricted = true)
        val fresh = DrivingInteraction.Known(false)
        assertFalse(restricted.editingAllowed)
        assertTrue(fresh.editingAllowed)
        assertFalse(DrivingInteraction.Unknown("연결이 끊겼어요").editingAllowed)
    }

    @Test fun invalidPlatformContentLimitsCannotBecomeAnApprovedKnownState() {
        assertThrows(IllegalArgumentException::class.java) { DrivingInteraction.Known(true, maximumTextLength = -1) }
        assertThrows(IllegalArgumentException::class.java) { DrivingInteraction.Known(true, maximumContentItems = -1) }
        assertThrows(IllegalArgumentException::class.java) { DrivingInteraction.Known(true, maximumContentDepth = -1) }
    }
}
