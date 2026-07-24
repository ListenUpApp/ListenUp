package com.calypsan.listenup.client.design.haptics

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Wraps a [HapticFeedback], forwarding only while [isEnabled] returns `true`. Installed at the
 * app root over `LocalHapticFeedback` so the user's haptics toggle silences *all* haptics —
 * both the app's own [Haptics] calls and any framework component that uses `LocalHapticFeedback`.
 * [isEnabled] is read live on every call so toggling the setting takes effect immediately.
 */
internal class GatedHapticFeedback(
    private val delegate: HapticFeedback,
    private val isEnabled: () -> Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (isEnabled()) delegate.performHapticFeedback(hapticFeedbackType)
    }
}
