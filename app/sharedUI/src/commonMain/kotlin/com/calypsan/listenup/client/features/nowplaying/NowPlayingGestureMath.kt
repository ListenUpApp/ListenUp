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
 * Pure gesture-decision math for the mini player's swipe-up-to-expand.
 *
 * Extracted so the threshold and fling decisions are unit-testable without a running composition,
 * mirroring the iOS side's `PlayerGestureMath` — the two platforms answer "was that a swipe?" the
 * same way, and both can be checked without a device.
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
     * iOS expresses the same idea through `predictedEndTranslation` — where the drag *would* end
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
 * Applies to BOTH bars: the floating pill and the wide docked bar. Which one you get is decided by
 * live window width, so on a foldable the docked bar *is* the mini player once the device is opened
 * — wiring only the floating one leaves the gesture missing exactly where the screen is biggest.
 *
 * Travel is accumulated in dp so the decision is density-independent, and evaluated only on release:
 * a drag that never commits leaves the bar exactly as it was. This sits outside the bar's own click
 * handling, so the inner play/pause and skip buttons keep their taps and a drag merely cancels the
 * press — the same arrangement iOS reaches with `simultaneousGesture`.
 */
@Composable
fun Modifier.swipeUpToExpand(onExpand: () -> Unit): Modifier {
    val density = LocalDensity.current
    var travelDp by remember { mutableFloatStateOf(0f) }
    val dragState =
        rememberDraggableState { deltaPx ->
            travelDp += with(density) { deltaPx.toDp().value }
        }
    return draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        onDragStarted = { travelDp = 0f },
        onDragStopped = { velocityPxPerSec ->
            val velocityDpPerSec = with(density) { velocityPxPerSec.toDp().value }
            if (NowPlayingGestureMath.shouldExpand(travelDp, velocityDpPerSec)) onExpand()
        },
    )
}
