package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
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
internal fun Modifier.dragToOpen(
    morph: PlayerMorphState,
    enabled: Boolean,
): Modifier {
    val haptics = LocalHaptics.current
    val density = LocalDensity.current.density
    val state = rememberDraggableState { deltaPx -> morph.dragBy(-deltaPx, haptics) }
    // The bar leaving mid-drag (a book unloading) must not strand the morph half risen.
    DisposableEffect(morph) { onDispose { morph.cancelDrag() } }
    return draggable(
        state = state,
        orientation = Orientation.Vertical,
        enabled = enabled,
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
    val travel = anchors.barBounds?.run { top.takeIf { it > 0f } } ?: return
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

/**
 * Drag Now Playing down into the bar, by the same rule as opening (canvas `GestureAndroid`, DRAG DOWN TO CLOSE).
 * Anywhere on the sheet except the scrubber: overflowing content scrolls first, and the collapse takes over only at
 * the top, so the scroll's leftover downward travel moves the morph and a reversal gives it back first.
 */
@Composable
internal fun Modifier.dragToClose(
    morph: PlayerMorphState,
    enabled: Boolean,
): Modifier {
    val haptics = LocalHaptics.current
    val density = LocalDensity.current.density
    val gesture = remember(morph) { CloseGesture(morph) }
    gesture.haptics = haptics
    gesture.density = density
    gesture.enabled = enabled
    val state = rememberDraggableState { deltaPx -> if (!gesture.excluded) morph.dragBy(-deltaPx, haptics) }
    // Now Playing (or the scrolling content whose fling would have released it) leaving mid-close settles the morph.
    DisposableEffect(morph) { onDispose { morph.cancelDrag() } }
    return nestedScroll(gesture)
        .pointerInput(morph) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                gesture.excluded = morph.anchors.pageRect(MorphPart.Line)?.contains(down.position) == true
            }
        }.draggable(
            state = state,
            orientation = Orientation.Vertical,
            enabled = enabled,
            onDragStarted = { if (!gesture.excluded) morph.snapDrag(morph.progress) },
            onDragStopped = { velocityPx -> if (!gesture.excluded) morph.releaseDrag(velocityPx / density, density) },
        )
}

/** The close drag's nested-scroll half: what overflowing content leaves over at its top moves the morph. */
private class CloseGesture(
    private val morph: PlayerMorphState,
) : NestedScrollConnection {
    var haptics: Haptics? = null
    var density: Float = 1f

    /** True while the current press began on the scrubber, whose drag is its own. */
    var excluded: Boolean = false

    /** False where Now Playing fades through rather than morphs: the content scrolls, and nothing closes. */
    var enabled: Boolean = true

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        // Reversing mid-close: the finger's rise goes back to the morph before the content scrolls.
        if (!enabled || excluded || source != NestedScrollSource.UserInput || !morph.isDragging ||
            available.y >= 0f
        ) {
            return Offset.Zero
        }
        haptics?.let { morph.dragBy(-available.y, it) }
        return Offset(0f, available.y)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (!enabled || excluded || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        haptics?.let { morph.dragBy(-available.y, it) }
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!morph.isDragging) return Velocity.Zero
        morph.releaseDrag(available.y / density, density)
        return available
    }

    // A scroll that ends without a fling still lets go of the morph it was moving.
    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity,
    ): Velocity {
        morph.cancelDrag()
        return Velocity.Zero
    }
}
