package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific back handler.
 *
 * - Android: Intercepts the system back gesture/button
 * - Desktop: No-op (no system back button concept)
 *
 * @param enabled Whether the back handler is active
 * @param onBack Callback when back is triggered
 */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

/**
 * Platform-specific predictive back handler.
 *
 * Receives a [Flow] of gesture progress values (0.0 → 1.0) during a swipe.
 * On commit, the flow completes and [onBack] returns; on cancel, the flow
 * completes without commit and the suspending lambda is canceled.
 *
 * - **Android (API 33+):** delegates to Compose `PredictiveBackHandler`.
 * - **Desktop:** no-op (no system gesture).
 * - **iOS:** no-op (no system back gesture in this app shell).
 *
 * @param enabled Whether the handler is active.
 * @param onBack Suspending callback receiving the progress flow. Cancellation unwinds the gesture.
 */
@Composable
expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onBack: suspend (progress: Flow<Float>) -> Unit,
)
