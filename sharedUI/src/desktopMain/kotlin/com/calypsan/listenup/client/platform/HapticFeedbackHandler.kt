package com.calypsan.listenup.client.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop no-op implementation of haptic feedback.
 * Desktop devices don't have haptic feedback hardware.
 */
private object NoOpHapticFeedbackHandler : HapticFeedbackHandler {
    override fun performClick() {
        // No-op on desktop
    }

    override fun performLongPress() {
        // No-op on desktop
    }

    override fun performTick() {
        // No-op on desktop
    }
}

/**
 * Desktop implementation returns a no-op handler since desktop doesn't have haptic feedback.
 */
@Composable
actual fun rememberHapticFeedback(): HapticFeedbackHandler = remember { NoOpHapticFeedbackHandler }
