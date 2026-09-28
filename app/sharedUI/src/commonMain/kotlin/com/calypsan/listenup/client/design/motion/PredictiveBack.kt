package com.calypsan.listenup.client.design.motion

import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.util.BackGestureEdge

/** Material's predictive-back preview: the surface being left shrinks to this scale. */
internal const val PREDICTIVE_BACK_SCALE = 0.9f

/** How far in from the window's edge a surface shrinking under predictive back stops travelling. */
internal val PredictiveBackEdgeMargin = 8.dp

/** The drift is a twentieth of the surface's width, per Material's predictive-back guidance. */
internal const val PREDICTIVE_BACK_SHIFT_DIVISOR = 20

/**
 * How a full-screen surface looks partway through a predictive back gesture.
 *
 * @property scale the uniform scale, from 1 down to [PREDICTIVE_BACK_SCALE]
 * @property translationX the horizontal drift in px, away from the swipe's edge
 * @property cornerFraction how far the corners have rounded toward the sheet's corner radius, 0 to 1
 * @property alpha the surface's opacity; it stays opaque, so the page beneath never shows through
 */
internal data class PredictiveBackPreview(
    val scale: Float,
    val translationX: Float,
    val cornerFraction: Float,
    val alpha: Float,
)

/**
 * Material's back preview for a full-screen surface, at [progress] (0 to 1) through the gesture: it
 * shrinks toward [PREDICTIVE_BACK_SCALE], drifts away from [edge] by a twentieth of [widthPx] less
 * [edgeMarginPx], and rounds its corners, all while staying opaque. It does not fade: a fade would
 * show the page beneath through the surface, and the exit transition owns the fade on commit.
 */
internal fun predictiveBackPreview(
    progress: Float,
    edge: BackGestureEdge,
    widthPx: Float,
    edgeMarginPx: Float,
): PredictiveBackPreview {
    val direction =
        when (edge) {
            BackGestureEdge.Left -> 1f
            BackGestureEdge.Right -> -1f
            BackGestureEdge.None -> 0f
        }
    val maxDrift = (widthPx / PREDICTIVE_BACK_SHIFT_DIVISOR - edgeMarginPx).coerceAtLeast(0f)
    return PredictiveBackPreview(
        scale = 1f - progress * (1f - PREDICTIVE_BACK_SCALE),
        translationX = direction * progress * maxDrift,
        cornerFraction = progress,
        alpha = 1f,
    )
}
