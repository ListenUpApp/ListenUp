package com.calypsan.listenup.client.design.timeline

import kotlin.math.abs
import kotlin.math.roundToLong

/** Vertical pull, in dp, at which each step takes over. Below the first, the drag runs at full speed. */
private const val HALF_AT_DP = 48f
private const val FIFTH_AT_DP = 120f
private const val FINE_AT_DP = 240f

/**
 * How much a horizontal drag is slowed by pulling away from the lane.
 *
 * The editor's hardest constraint is that a 65-hour book is ~195 seconds per pixel when drawn
 * whole, so no amount of careful rendering makes the full view precise. Zoom fixes the baseline;
 * this multiplies whatever baseline you already have, which is why it keeps working at every zoom
 * level and on every book length.
 *
 * Discrete steps rather than a continuous curve, because the HUD tells the user which one they are
 * in ([label]) and "somewhere around a seventh" is not a thing anyone can aim for.
 *
 * @property ratio factor applied to the lane's own px→time scale. Smaller is finer.
 * @property label what the HUD shows; the user is being told what they got.
 */
enum class FineScrubStep(
    val ratio: Float,
    val label: String,
) {
    /** No pull: the drag moves at the lane's own scale. */
    Full(ratio = 1f, label = "1×"),

    /** A short pull away. */
    Half(ratio = 0.5f, label = "½×"),

    /** The working step for most boundary nudging. */
    Fifth(ratio = 0.2f, label = "⅕×"),

    /** The deepest step — roughly 25× finer, enough to place a boundary by ear. */
    Fine(ratio = 0.04f, label = "25×"),
    ;

    /**
     * The span of time a [dragPx] horizontal drag covers at this step, given the lane's
     * [msPerPixel] (see [TimelineGeometry.msPerPixel]).
     */
    fun timeDeltaMs(
        dragPx: Float,
        msPerPixel: Double,
    ): Long = (dragPx.toDouble() * msPerPixel * ratio).roundToLong()
}

/**
 * The step a pointer has pulled itself into.
 *
 * [verticalDistanceDp] is taken as a magnitude: which direction counts as "away" depends on where
 * the marker sits on screen, and a marker near the top of the lane can only be pulled downward.
 * Ignoring the sign is what makes the gesture reachable everywhere rather than only below the
 * lane's middle.
 *
 * [shiftHeld] is the desktop shortcut the spec gives mouse users — an explicit request for the
 * finest step, so it wins outright rather than being blended with distance.
 */
fun fineScrubStepFor(
    verticalDistanceDp: Float,
    shiftHeld: Boolean = false,
): FineScrubStep {
    if (shiftHeld) return FineScrubStep.Fine
    return when (abs(verticalDistanceDp)) {
        in 0f..<HALF_AT_DP -> FineScrubStep.Full
        in HALF_AT_DP..<FIFTH_AT_DP -> FineScrubStep.Half
        in FIFTH_AT_DP..<FINE_AT_DP -> FineScrubStep.Fifth
        else -> FineScrubStep.Fine
    }
}
