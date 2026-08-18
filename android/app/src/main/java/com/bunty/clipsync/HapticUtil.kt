package com.bunty.clipsync

import android.view.HapticFeedbackConstants
import android.view.View

object HapticUtil {
    /** Light tap haptic — use for tab switches */
    fun performUIHaptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Stronger virtual key haptic — use for button presses */
    fun performVirtualKeyHaptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
