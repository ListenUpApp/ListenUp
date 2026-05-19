package com.calypsan.listenup.core

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Provides subtle haptic feedback for UI interactions.
 * Respects the user's haptic feedback preference setting.
 *
 * Usage:
 * ```
 * val hapticEnabled by settingsRepository.hapticFeedbackEnabled.collectAsStateWithLifecycle()
 * val haptics = rememberHapticFeedback { hapticEnabled }
 *
 * IconButton(onClick = {
 *     haptics.tick()
 *     onSkipForward()
 * }) { ... }
 * ```
 */
class HapticFeedbackHelper(
    private val view: View,
    private val isEnabled: () -> Boolean,
) {
    /**
     * Light tap feedback for button presses and selections.
     * Use for: playback controls, list item taps, toggle switches.
     */
    fun tick() {
        if (!isEnabled()) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /**
     * Confirmation feedback for successful actions.
     * Use for: successful operations, completion of tasks.
     */
    fun confirm() {
        if (!isEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Rejection feedback for invalid or failed actions.
     * Use for: validation errors, blocked actions, failed operations.
     */
    fun reject() {
        if (!isEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * Heavy click feedback for significant interactions.
     * Use for: drag-and-drop release, long press activation, major state changes.
     */
    fun heavyClick() {
        if (!isEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
}

/**
 * Remember a [HapticFeedbackHelper] for the current composition.
 *
 * @param isEnabled Lambda that returns whether haptic feedback is enabled.
 *                  This is evaluated on each haptic call, allowing dynamic toggling.
 */
@Composable
fun rememberHapticFeedback(isEnabled: () -> Boolean): HapticFeedbackHelper {
    val view = LocalView.current
    return remember(view) { HapticFeedbackHelper(view, isEnabled) }
}
