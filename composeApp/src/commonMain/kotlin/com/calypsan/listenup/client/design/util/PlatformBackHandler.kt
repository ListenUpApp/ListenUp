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
 *
 * - **Android (API 33+):** delegates to Compose `PredictiveBackHandler`.
 * - **Desktop:** no-op (no system gesture).
 * - **iOS:** no-op (no system back gesture in this app shell).
 *
 * @param enabled Whether the handler is active.
 * @param onBack Suspending callback receiving the progress flow. On gesture commit the flow
 *   completes normally and [onBack] returns. On gesture cancel the flow is cancelled with a
 *   [kotlinx.coroutines.CancellationException] — callers must re-throw it to preserve structured
 *   concurrency, resetting any in-progress animation in the `catch` rather than a `finally` so the
 *   committed path is left untouched for its exit transition.
 */
@Composable
expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onBack: suspend (progress: Flow<Float>) -> Unit,
)
