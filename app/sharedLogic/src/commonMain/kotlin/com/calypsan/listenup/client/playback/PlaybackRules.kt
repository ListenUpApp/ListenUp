package com.calypsan.listenup.client.playback

import kotlin.math.roundToInt

/** The slowest the player is asked to go. Below this a narrator stops being intelligible. */
const val PLAYBACK_SPEED_MIN: Float = 0.5f

/** The fastest. Beyond this the platform players stop pitch-correcting cleanly. */
const val PLAYBACK_SPEED_MAX: Float = 3.0f

/**
 * The finest adjustment offered between rungs.
 *
 * A ladder is enough for the rates people name out loud; this is for the pace that has no name —
 * the one that is right for a particular narrator, a few hundredths off a preset.
 */
const val PLAYBACK_SPEED_INCREMENT: Float = 0.05f

/** Rounds [speed] to the nearest [PLAYBACK_SPEED_INCREMENT], so a dragged control lands on a rate. */
fun snapPlaybackSpeed(speed: Float): Float = (speed / PLAYBACK_SPEED_INCREMENT).roundToInt() * PLAYBACK_SPEED_INCREMENT

/**
 * How far a transport skip lands from [currentPositionMs].
 *
 * The distance is multiplied by [speed] deliberately. A listener at 1.5x who asks to go "back ten
 * seconds" means ten seconds of *listening*, which is fifteen seconds of book — skipping a fixed
 * book duration would quietly shrink the gesture the faster you listen, which is exactly when
 * catching a missed sentence matters most.
 *
 * Clamped to the book at both ends, so a skip near an edge lands on the edge rather than failing.
 *
 * Callers are expected to have a loaded book: with no timeline there is no meaningful
 * [totalDurationMs], and a forward skip would clamp to zero and throw the listener back to the
 * start. Callers (`PlayerSession`) guard on a known duration first.
 */
fun skipTargetMs(
    currentPositionMs: Long,
    seconds: Int,
    speed: Float,
    totalDurationMs: Long,
    forward: Boolean,
): Long {
    val distanceMs = skipDistanceMs(seconds, speed)
    return if (forward) {
        (currentPositionMs + distanceMs).coerceAtMost(totalDurationMs)
    } else {
        (currentPositionMs - distanceMs).coerceAtLeast(0L)
    }
}

/**
 * How much book a skip of [seconds] covers at [speed]: seconds of listening, so it scales with the rate.
 * The app and every system skip (notification, lock screen, headset, car) move this far.
 */
fun skipDistanceMs(
    seconds: Int,
    speed: Float,
): Long = (seconds * speed * 1000).toLong()

/** Which way one press on the speed ladder moves the rate. */
enum class SpeedStep {
    /** One [PLAYBACK_SPEED_INCREMENT] up. */
    Faster,

    /** One [PLAYBACK_SPEED_INCREMENT] down. */
    Slower,
}

/**
 * One [PLAYBACK_SPEED_INCREMENT] up or down from [speed], clamped to [PLAYBACK_SPEED_MIN]..[PLAYBACK_SPEED_MAX].
 *
 * Counted in whole hundredths and divided once, so a hundred presses land exactly on a rung instead of
 * accumulating `0.05f`'s representation error.
 */
fun stepPlaybackSpeed(
    speed: Float,
    up: Boolean,
): Float {
    val steps = (speed / PLAYBACK_SPEED_INCREMENT).roundToInt() + if (up) 1 else -1
    return (steps * 5 / 100f).coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX)
}
