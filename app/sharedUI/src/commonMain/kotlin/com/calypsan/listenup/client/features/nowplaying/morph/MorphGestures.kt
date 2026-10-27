package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.player.crossesHalfway
import com.calypsan.listenup.client.presentation.player.progressVelocity
import com.calypsan.listenup.client.presentation.player.settleTarget

/**
 * Drag the bar up into Now Playing, both ways, 1:1 (canvas `GestureAndroid`). Anywhere on the bar, buttons included:
 * under the platform's touch slop (about [com.calypsan.listenup.client.presentation.player.PlayerMorph.TOUCH_SLOP_DP])
 * a press is still a tap, and past it the drag owns the gesture and the inner button's press is cancelled. Each
 * crossing of halfway ticks. On release, velocity decides first, then the position ([settleTarget]), and the default
 * spatial spring carries the fling's velocity.
 *
 * Screen readers never reach it: a drag has no semantics, and the bar's click stays the way in.
 */
@Composable
internal fun Modifier.dragToOpen(morph: PlayerMorphState): Modifier {
    val haptics = LocalHaptics.current
    val density = LocalDensity.current.density
    val state = rememberDraggableState { deltaPx -> morph.dragBy(-deltaPx, haptics) }
    return draggable(
        state = state,
        orientation = Orientation.Vertical,
        onDragStarted = { morph.snapDrag(morph.progress) },
        onDragStopped = { velocityPx -> morph.releaseDrag(velocityPx / density, density) },
    )
}

/**
 * Moves a held morph by [risePx] of finger travel (positive upward) over the full travel, the bar's top edge to the
 * window's, ticking [haptics] at each crossing of halfway.
 */
internal fun PlayerMorphState.dragBy(
    risePx: Float,
    haptics: Haptics,
) {
    val travel = anchors.barBounds?.top?.takeIf { it > 0f } ?: return
    val before = progress
    snapDrag(before + risePx / travel)
    if (crossesHalfway(before, progress)) haptics.thresholdActivate()
}

/** Lets a held morph go at [velocityDpPerSec] (negative upward): the shared release rule picks the end. */
internal fun PlayerMorphState.releaseDrag(
    velocityDpPerSec: Float,
    density: Float,
) {
    if (!isDragging) return
    val travelDp = (anchors.barBounds?.top ?: 0f) / density
    release(settleTarget(progress, velocityDpPerSec), progressVelocity(velocityDpPerSec, travelDp))
}
