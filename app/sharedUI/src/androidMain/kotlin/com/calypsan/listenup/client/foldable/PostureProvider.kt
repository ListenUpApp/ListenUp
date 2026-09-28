package com.calypsan.listenup.client.foldable

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/**
 * Hosts [LocalFold], derived from `WindowInfoTracker`: the posture, and the hinge's bounds in the
 * window so a layout can split at it.
 *
 * - [Posture.TABLETOP]: device half-open with a horizontal hinge.
 * - [Posture.BOOK]: device half-open with a vertical hinge.
 * - [Posture.NORMAL]: anything else (closed, flat, single screen).
 */
@Composable
fun PostureProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val fold =
        if (activity != null) {
            WindowInfoTracker
                .getOrCreate(context)
                .windowLayoutInfo(activity)
                .map { info -> fold(info.displayFeatures) }
                .collectAsStateWithLifecycle(
                    initialValue = Fold.None,
                    lifecycle = activity.lifecycle,
                    minActiveState = Lifecycle.State.STARTED,
                ).value
        } else {
            Fold.None
        }
    CompositionLocalProvider(LocalFold provides fold) {
        content()
    }
}

private fun fold(features: List<DisplayFeature>): Fold {
    val folding = features.filterIsInstance<FoldingFeature>().firstOrNull()
    val bounds = folding?.bounds?.let { IntRect(it.left, it.top, it.right, it.bottom) }
    return classifyFold(folding?.state, folding?.orientation, bounds)
}

/**
 * Pure mapping from extracted [FoldingFeature] properties to a [Fold]: the [classifyPosture] posture
 * plus the hinge's window [bounds], which are kept even when the fold is flat. No folding feature
 * (`null` inputs) is [Fold.None].
 */
internal fun classifyFold(
    state: FoldingFeature.State?,
    orientation: FoldingFeature.Orientation?,
    bounds: IntRect?,
): Fold = Fold(classifyPosture(state, orientation), bounds)

/**
 * Pure mapping from extracted [FoldingFeature] properties to a [Posture] value.
 *
 * Visible for testing — callers outside the `foldable` package should use [LocalFold]
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
