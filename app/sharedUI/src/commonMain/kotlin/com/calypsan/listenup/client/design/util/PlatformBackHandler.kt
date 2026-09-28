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

/** The screen edge a predictive back swipe started from; [None] for a gesture with no edge. */
enum class BackGestureEdge { Left, Right, None }

/**
 * One moment of a predictive back gesture.
 *
 * @property progress how far through the gesture, from 0 to 1
 * @property edge the edge the swipe started from, which the preview drifts away from
 */
data class BackGestureFrame(
    val progress: Float,
    val edge: BackGestureEdge,
)

/**
 * Platform-specific predictive back handler.
 *
 * Receives a [Flow] of [BackGestureFrame]s (progress 0.0 → 1.0, and the swipe's edge) during a swipe.
 *
 * - **Android (API 33+):** delegates to Compose `PredictiveBackHandler`.
 * - **Desktop:** no-op (no system gesture).
 * - **iOS:** no-op (no system back gesture in this app shell).
 *
 * @param enabled Whether the handler is active.
 * @param onBack Suspending callback receiving the gesture flow. On gesture commit the flow
 *   completes normally and [onBack] returns. On gesture cancel the flow is cancelled with a
 *   [kotlinx.coroutines.CancellationException] — callers must re-throw it to preserve structured
 *   concurrency, resetting any in-progress animation in the `catch` rather than a `finally` so the
 *   committed path is left untouched for its exit transition.
 */
@Composable
expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onBack: suspend (gesture: Flow<BackGestureFrame>) -> Unit,
)
