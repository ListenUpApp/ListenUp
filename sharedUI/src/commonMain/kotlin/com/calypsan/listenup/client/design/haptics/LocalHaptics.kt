package com.calypsan.listenup.client.design.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Provides the app's semantic [Haptics]. Defaults to [NoOpHaptics] so platforms without a
 * provider (e.g. Desktop) simply do nothing. Android installs a real, gated instance via
 * [ProvideHaptics]. Read with `LocalHaptics.current`.
 */
val LocalHaptics = staticCompositionLocalOf<Haptics> { NoOpHaptics }

/**
 * Installs gated haptics for [content]. Replaces the framework `LocalHapticFeedback` with a
 * [GatedHapticFeedback] (so framework haptics also respect the toggle) and exposes the semantic
 * [LocalHaptics] over the same gate. [hapticFeedbackEnabled] is observed live; flipping it takes
 * effect immediately without recreating the gate.
 */
@Composable
fun ProvideHaptics(
    hapticFeedbackEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val enabled = rememberUpdatedState(hapticFeedbackEnabled)
    val platformFeedback = LocalHapticFeedback.current
    val gatedFeedback =
        remember(platformFeedback) {
            GatedHapticFeedback(platformFeedback) { enabled.value }
        }
    val haptics = remember(gatedFeedback) { HapticFeedbackHaptics(gatedFeedback) }
    CompositionLocalProvider(
        LocalHapticFeedback provides gatedFeedback,
        LocalHaptics provides haptics,
        content = content,
    )
}
