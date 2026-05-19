package com.calypsan.listenup.client.foldable

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/**
 * Hosts a [Posture] composition local derived from `WindowInfoTracker`.
 *
 * - [Posture.TABLETOP]: device half-open with a horizontal hinge.
 * - [Posture.BOOK]: device half-open with a vertical hinge.
 * - [Posture.NORMAL]: anything else (closed, flat, single screen).
 */
@Composable
fun PostureProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val posture =
        if (activity != null) {
            WindowInfoTracker
                .getOrCreate(context)
                .windowLayoutInfo(activity)
                .map { info -> posture(info.displayFeatures) }
                .collectAsStateWithLifecycle(
                    initialValue = Posture.NORMAL,
                    lifecycle = activity.lifecycle,
                    minActiveState = Lifecycle.State.STARTED,
                ).value
        } else {
            Posture.NORMAL
        }
    CompositionLocalProvider(LocalPosture provides posture) {
        content()
    }
}

private fun posture(features: List<DisplayFeature>): Posture {
    val folding = features.filterIsInstance<FoldingFeature>().firstOrNull()
    return classifyPosture(folding?.state, folding?.orientation)
}

/**
 * Pure mapping from extracted [FoldingFeature] properties to a [Posture] value.
 *
 * Visible for testing — callers outside the `foldable` package should use [LocalPosture]
 * rather than this helper directly.
 *
 * Rules:
 * - [FoldingFeature.State.HALF_OPENED] + [FoldingFeature.Orientation.HORIZONTAL] → [Posture.TABLETOP]
 * - [FoldingFeature.State.HALF_OPENED] + [FoldingFeature.Orientation.VERTICAL] → [Posture.BOOK]
 * - Any other combination, or `null` inputs (no folding feature present) → [Posture.NORMAL]
 */
internal fun classifyPosture(
    state: FoldingFeature.State?,
    orientation: FoldingFeature.Orientation?,
): Posture {
    if (state != FoldingFeature.State.HALF_OPENED) return Posture.NORMAL
    return when (orientation) {
        FoldingFeature.Orientation.HORIZONTAL -> Posture.TABLETOP
        FoldingFeature.Orientation.VERTICAL -> Posture.BOOK
        else -> Posture.NORMAL
    }
}
