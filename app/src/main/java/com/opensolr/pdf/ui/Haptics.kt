package com.opensolr.pdf.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** The taps the app gives back, the same set as the other Opensolr apps. */
object Haptics {

    /** One tap: [strong] for the main action, the lighter one for a minor step. */
    fun tick(view: View, strong: Boolean) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (strong) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    /** The light tap every button gives back. */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
