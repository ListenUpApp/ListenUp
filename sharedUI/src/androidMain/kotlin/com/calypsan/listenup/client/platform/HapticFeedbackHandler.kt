package com.calypsan.listenup.client.platform

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
 * Android implementation of haptic feedback that wraps the platform HapticFeedback.
 */
private class AndroidHapticFeedbackHandler(
    private val platformHaptics: HapticFeedback,
    private val enabled: Boolean,
) : HapticFeedbackHandler {
    override fun performClick() {
        if (enabled) {
            platformHaptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    override fun performLongPress() {
        if (enabled) {
            platformHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    override fun performTick() {
        if (enabled) {
            platformHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
}

/**
 * Android implementation that uses system haptic feedback and respects user preferences.
 */
@Composable
actual fun rememberHapticFeedback(): HapticFeedbackHandler {
    val localPreferences: LocalPreferences = koinInject()
    val platformHaptics = LocalHapticFeedback.current
    val hapticEnabled by localPreferences.hapticFeedbackEnabled.collectAsStateWithLifecycle()

    return remember(platformHaptics, hapticEnabled) {
        AndroidHapticFeedbackHandler(platformHaptics, hapticEnabled)
    }
}
