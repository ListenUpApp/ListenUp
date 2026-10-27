package com.calypsan.listenup.client.presentation.player

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/** The two ends of Now Playing's open/close motion: the mini player, and the full player. */
enum class MorphEnd { Bar, NowPlaying }

/**
 * How Now Playing travels between its two ends: elements fly ([Morph]), or, with animations removed, Now Playing
 * fades in over the bar where it sits ([Crossfade], the canvas's `Reduce` board).
 */
enum class MorphMode { Morph, Crossfade }

/**
 * The numbers the mini player morph is built to (canvas `Spec`, `GestureAndroid` and `GestureWeb`), decided once so
 * Android's drag and web's drag obey the same rule. Springs are (stiffness, damping ratio), M3 Expressive's tokens.
 */
object PlayerMorph {
    /** A fling faster than this decides on its own (dp/s): today's trigger value, kept so a flick works as it did. */
    const val FLING_DP_PER_SEC: Float = 720f

    /** Below the fling, a release's position decides against this line. */
    const val HALFWAY: Float = 0.5f

    /** Under this movement (dp) a press is still a tap; past it the drag owns the gesture. */
    const val TOUCH_SLOP_DP: Float = 8f

    /** A release and Collapse: M3 Expressive's default spatial spring. */
    val SETTLE_SPRING: SpringParameters = SpringParameters(stiffness = 380f, dampingRatio = 0.8f)

    /** A tap: M3 Expressive's slow spatial spring, which is the one the open used before the morph. */
    val TAP_SPRING: SpringParameters = SpringParameters(stiffness = 200f, dampingRatio = 0.8f)
}

/** A damped spring's two numbers, with unit mass. */
data class SpringParameters(
    val stiffness: Float,
    val dampingRatio: Float,
)

/**
 * Where a released drag settles (canvas `GestureAndroid`, RELEASE): velocity first, then the position against
 * [PlayerMorph.HALFWAY]. [velocityDpPerSec] is negative upward, as pointer coordinates are. Pure.
 */
fun settleTarget(
    progress: Float,
    velocityDpPerSec: Float,
): MorphEnd =
    when {
        velocityDpPerSec < -PlayerMorph.FLING_DP_PER_SEC -> MorphEnd.NowPlaying
        velocityDpPerSec > PlayerMorph.FLING_DP_PER_SEC -> MorphEnd.Bar
        progress > PlayerMorph.HALFWAY -> MorphEnd.NowPlaying
        else -> MorphEnd.Bar
    }

/** Whether moving from [previous] to [next] crosses the halfway line, either way: the haptic tick's moment. Pure. */
fun crossesHalfway(
    previous: Float,
    next: Float,
): Boolean = (previous > PlayerMorph.HALFWAY) != (next > PlayerMorph.HALFWAY)

/** The share of a flight's horizontal ([x]) and vertical ([y]) travel covered at one progress. */
internal data class Arc(
    val x: Float,
    val y: Float,
)

/**
 * The cover's and the title's path (canvas `MorphAndroid`): x eases in (`p^1.5`) and y eases out
 * (`1 − (1 − p)^1.5`), so a part swings up before it centres. Past 0 and 1 it extrapolates, sign-preserving, so a
 * spring's overshoot shows on the cover rather than freezing it.
 */
internal fun arc(p: Float): Arc = Arc(x = signedPow(p, ARC_EXPONENT), y = 1f - signedPow(1f - p, ARC_EXPONENT))

private const val ARC_EXPONENT = 1.5f

private fun signedPow(
    value: Float,
    exponent: Float,
): Float = sign(value) * abs(value).pow(exponent)

/** An axis-aligned rectangle, in whatever coordinate space both ends of a flight were measured in. */
data class MorphRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * Where a shared part (cover, title, play button, progress line) is at progress [p] on its way from [from] (the bar)
 * to [to] (Now Playing): left by the arc's x, top by its y, and size linear in [p]. Pure.
 */
fun flight(
    from: MorphRect,
    to: MorphRect,
    p: Float,
): MorphRect {
    val path = arc(p)
    return MorphRect(
        left = from.left + (to.left - from.left) * path.x,
        top = from.top + (to.top - from.top) * path.y,
        width = from.width + (to.width - from.width) * p,
        height = from.height + (to.height - from.height) * p,
    )
}

/**
 * Material's predictive back preview (the [scale], the edge [translationX] drift and the [cornerFraction] rounding) as
 * the gesture left it, carried into a committed close and weighted away as the morph returns to the bar.
 */
data class BackPreviewWeight(
    val scale: Float,
    val translationX: Float,
    val cornerFraction: Float,
) {
    /** The preview at morph progress [p]: all of it at 1 (where the commit started), none of it at the bar. */
    fun at(p: Float): BackPreviewWeight {
        val weight = p.coerceIn(0f, 1f)
        return BackPreviewWeight(
            scale = 1f + (scale - 1f) * weight,
            translationX = translationX * weight,
            cornerFraction = cornerFraction * weight,
        )
    }

    /** The holder of [None]. */
    companion object {
        /** No preview: full size, no drift, no rounding. */
        val None: BackPreviewWeight = BackPreviewWeight(scale = 1f, translationX = 0f, cornerFraction = 0f)
    }
}

/** The morph's mode: the canvas's `Reduce` crossfade when animations are removed, otherwise the morph. Pure. */
fun morphMode(reduceMotion: Boolean): MorphMode = if (reduceMotion) MorphMode.Crossfade else MorphMode.Morph

/**
 * Progress from a drag: how far the sheet's top edge ([sheetTopPx]) has risen over the full travel, the bar's top
 * edge ([barTopPx]) to the top of the window. Clamped to 0..1, because a finger never overshoots. Pure.
 */
fun progressOf(
    sheetTopPx: Float,
    barTopPx: Float,
): Float = if (barTopPx <= 0f) 1f else (1f - sheetTopPx / barTopPx).coerceIn(0f, 1f)

/**
 * A release velocity in dp/s (negative upward) as progress per second, for the spring to carry: the travel is
 * [travelDp]. Pure.
 */
fun progressVelocity(
    velocityDpPerSec: Float,
    travelDp: Float,
): Float = if (travelDp <= 0f) 0f else -velocityDpPerSec / travelDp
