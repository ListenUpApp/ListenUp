package com.calypsan.listenup.client.platform

import androidx.compose.runtime.Composable

/**
 * Platform-agnostic haptic feedback handler.
 *
 * Provides haptic feedback methods that work across platforms:
 * - Android: Uses system haptic feedback (vibration)
 * - Desktop: No-op (desktop doesn't have haptic feedback)
 */
interface HapticFeedbackHandler {
    /**
     * Perform a click haptic feedback.
     * Used for button taps and primary interactions.
     */
    fun performClick()

    /**
     * Perform a long press haptic feedback.
     * Used for context menus and hold interactions.
     */
    fun performLongPress()

    /**
     * Perform a segment tick haptic feedback.
     * Used for slider adjustments and segment changes.
     */
    fun performTick()
}

/**
 * Remember a haptic feedback handler that respects user preferences.
 *
 * Platform implementations:
 * - Android: Observes haptic preference from settings and performs actual haptics
 * - Desktop: Returns a no-op handler
 *
 * @return A [HapticFeedbackHandler] for performing haptic feedback
 */
@Composable
expect fun rememberHapticFeedback(): HapticFeedbackHandler
