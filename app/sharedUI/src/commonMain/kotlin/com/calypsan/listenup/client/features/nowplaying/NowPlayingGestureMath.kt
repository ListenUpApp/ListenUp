package com.calypsan.listenup.client.features.nowplaying

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
