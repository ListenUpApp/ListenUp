package com.calypsan.listenup.client.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent

/**
 * How far screens travel in the Material shared axis X: a short step that says "forward" or "back",
 * not a full-width slide carrying the whole page across the window.
 */
internal val SharedAxisTravel = 30.dp

/** How far in from the window's edge a screen shrinking under predictive back stops travelling. */
internal val PredictiveBackEdgeMargin = 8.dp

/** Material's predictive-back preview: the screen being left shrinks to this scale. */
internal const val PREDICTIVE_BACK_SCALE = 0.9f

/**
 * The authenticated graph's screen changes, in the theme's motion and the screen's density: see
 * [sharedAxisXPush], [sharedAxisXPop] and [predictiveBackPop]. A destination with a hero replaces
 * them with its container transform (`heroEntryTransitions`).
 */
internal class ScreenTransitions(
    private val motion: MotionScheme,
    private val travelPx: Int,
    private val edgeMarginPx: Int,
) {
    fun push(): ContentTransform = sharedAxisXPush(motion, travelPx)

    fun pop(): ContentTransform = sharedAxisXPop(motion, travelPx)

    fun predictivePop(
        @NavigationEvent.SwipeEdge swipeEdge: Int,
    ): ContentTransform = predictiveBackPop(motion, swipeEdge, edgeMarginPx)
}

/** [ScreenTransitions] for the current theme and density. */
@Composable
internal fun rememberScreenTransitions(): ScreenTransitions {
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    return remember(motion, density) {
        with(density) {
            ScreenTransitions(motion, SharedAxisTravel.roundToPx(), PredictiveBackEdgeMargin.roundToPx())
        }
    }
}

/**
 * Push, Material shared axis X. The outgoing screen steps back by [travelPx] and fades quickly; the
 * incoming one steps in from the same distance ahead and fades in behind it.
 */
internal fun sharedAxisXPush(
    motion: MotionScheme,
    travelPx: Int,
): ContentTransform =
    (slideInHorizontally(motion.defaultSpatialSpec()) { travelPx } + fadeIn(motion.defaultEffectsSpec()))
        .togetherWith(
            slideOutHorizontally(motion.defaultSpatialSpec()) { -travelPx } + fadeOut(motion.fastEffectsSpec()),
        )

/** Pop, the mirror of [sharedAxisXPush]. */
internal fun sharedAxisXPop(
    motion: MotionScheme,
    travelPx: Int,
): ContentTransform =
    (slideInHorizontally(motion.defaultSpatialSpec()) { -travelPx } + fadeIn(motion.defaultEffectsSpec()))
        .togetherWith(
            slideOutHorizontally(motion.defaultSpatialSpec()) { travelPx } + fadeOut(motion.fastEffectsSpec()),
        )

/**
 * Predictive back, Material's back preview. The screen being left shrinks to [PREDICTIVE_BACK_SCALE]
 * and drifts away from the edge the swipe started at, by a twentieth of its width less
 * [edgeMarginPx], while the screen beneath fades in. The gesture seeks this transition, so it follows
 * the finger and completes or springs back on release.
 *
 * The leaving screen does not fade: a seeked fade would run out long before the gesture does and
 * leave the user dragging nothing.
 */
internal fun predictiveBackPop(
    motion: MotionScheme,
    @NavigationEvent.SwipeEdge swipeEdge: Int,
    edgeMarginPx: Int,
): ContentTransform {
    val direction = predictiveBackDirection(swipeEdge)
    return fadeIn(motion.defaultEffectsSpec())
        .togetherWith(
            scaleOut(motion.defaultSpatialSpec(), targetScale = PREDICTIVE_BACK_SCALE) +
                slideOutHorizontally(motion.defaultSpatialSpec()) { fullWidth ->
                    direction * (fullWidth / PREDICTIVE_BACK_SHIFT_DIVISOR - edgeMarginPx).coerceAtLeast(0)
                },
        )
}

/**
 * Which way a screen drifts under predictive back: away from the swipe's edge, so a swipe from the
 * left pushes it right (+1) and one from the right pushes it left (-1). A button press has no edge and
 * does not drift (0).
 */
internal fun predictiveBackDirection(
    @NavigationEvent.SwipeEdge swipeEdge: Int,
): Int =
    when (swipeEdge) {
        NavigationEvent.EDGE_LEFT -> 1
        NavigationEvent.EDGE_RIGHT -> -1
        else -> 0
    }

/** The drift is a twentieth of the screen's width, per Material's predictive-back guidance. */
private const val PREDICTIVE_BACK_SHIFT_DIVISOR = 20
