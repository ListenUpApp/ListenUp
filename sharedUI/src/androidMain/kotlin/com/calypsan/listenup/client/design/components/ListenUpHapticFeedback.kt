package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import org.koin.compose.koinInject

/**
 * A haptic feedback wrapper that respects user preferences.
 *
 * Wraps the platform [HapticFeedback] and only performs haptics when:
 * 1. The user has enabled haptic feedback in settings
 * 2. The device supports haptic feedback
 *
 * Usage:
 * ```
 * val haptics = rememberHapticFeedback()
 *
 * Button(onClick = {
 *     haptics.performClick()
 *     // ... button action
 * }) { ... }
 * ```
 */
class ListenUpHapticFeedback(
    private val platformHaptics: HapticFeedback,
    private val enabled: Boolean,
) {
    /**
     * Perform a click haptic feedback.
     * Used for button taps and primary interactions.
     */
    fun performClick() {
        if (enabled) {
            platformHaptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    /**
     * Perform a long press haptic feedback.
     * Used for context menus and hold interactions.
     */
    fun performLongPress() {
        if (enabled) {
            platformHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    /**
     * Perform a segment tick haptic feedback.
     * Used for slider adjustments and segment changes.
     */
    fun performTick() {
        if (enabled) {
            platformHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
}

/**
 * Remember a haptic feedback instance that respects user preferences.
 *
 * This composable observes the haptic feedback preference from settings
 * and creates a [ListenUpHapticFeedback] that only performs haptics when enabled.
 *
 * @return A [ListenUpHapticFeedback] instance for performing haptic feedback
 */
@Composable
fun rememberHapticFeedback(localPreferences: LocalPreferences = koinInject()): ListenUpHapticFeedback {
    val platformHaptics = LocalHapticFeedback.current
    val hapticEnabled by localPreferences.hapticFeedbackEnabled.collectAsStateWithLifecycle()

    return remember(platformHaptics, hapticEnabled) {
        ListenUpHapticFeedback(platformHaptics, hapticEnabled)
    }
}
