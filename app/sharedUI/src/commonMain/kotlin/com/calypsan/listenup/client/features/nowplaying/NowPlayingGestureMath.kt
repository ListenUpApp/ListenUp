package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * Pure gesture-decision math for the swipe-up-to-expand trigger: the floating toolbar's, and the phone
 * bar's wherever Now Playing fades through rather than morphs (the phone bar's morph follows the finger
 * instead, `dragToOpen`).
 *
 * Extracted so the threshold and fling decisions are unit-testable without a running composition. iOS
 * answers "was that a swipe?" with the same numbers in `MiniPlayerSwipe` (40 pt, or a projected 120 pt).
 *
 * Values are in **dp** and **dp/second**, so the decision is density-independent: the same physical
 * flick expands on any screen.
 */
object NowPlayingGestureMath {
    /** Upward travel that commits the swipe on its own, however slowly it was made. */
    const val EXPAND_TRAVEL_DP: Float = -40f

    /**
     * Upward fling speed that commits the swipe regardless of distance, so a quick flick works
     * without dragging the full [EXPAND_TRAVEL_DP].
     *
     * iOS (`MiniPlayerSwipe`) expresses the same idea through `predictedEndTranslation` — where the drag *would* end
     * once momentum ran out. Compose reports a fling velocity instead, so this is that threshold
     * restated: SwiftUI projects roughly a sixth of a second of travel, and iOS commits at a
     * projected 120pt, which is the ~720 dp/s below.
     */
    const val EXPAND_FLING_DP_PER_SEC: Float = -720f

    /**
     * Whether an upward drag on the mini player should open the full player.
     *
     * [travelDp] and [velocityDpPerSec] are negative upward, matching the pointer coordinates the
     * caller already has. A downward drag satisfies neither test, so pulling the bar down does
     * nothing rather than expanding — the mini player has nowhere to go.
     */
    fun shouldExpand(
        travelDp: Float,
        velocityDpPerSec: Float,
    ): Boolean = travelDp < EXPAND_TRAVEL_DP || velocityDpPerSec < EXPAND_FLING_DP_PER_SEC
}

/**
 * Swipe up on a mini player to open the full player, alongside its tap.
 *
 * The floating toolbar's trigger, and the phone bar's wherever Now Playing fades through. Which bar
 * you get is decided by live window width, so on a foldable the toolbar *is* the mini player once the
 * device is opened.
 *
 * Travel is accumulated in dp so the decision is density-independent, and evaluated only on release:
 * a drag that never commits leaves the bar exactly as it was. This sits outside the bar's own click
 * handling, so the inner play/pause and skip buttons keep their taps and a drag merely cancels the
 * press — the same arrangement iOS reaches with `simultaneousGesture`.
 */
@Composable
fun Modifier.swipeUpToExpand(
    onExpand: () -> Unit,
    enabled: Boolean = true,
): Modifier {
    val density = LocalDensity.current
    var travelDp by remember { mutableFloatStateOf(0f) }
    val dragState =
        rememberDraggableState { deltaPx ->
            travelDp += with(density) { deltaPx.toDp().value }
        }
    return draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        enabled = enabled,
        onDragStarted = { travelDp = 0f },
        onDragStopped = { velocityPxPerSec ->
            val velocityDpPerSec = with(density) { velocityPxPerSec.toDp().value }
            if (NowPlayingGestureMath.shouldExpand(travelDp, velocityDpPerSec)) onExpand()
        },
    )
}
