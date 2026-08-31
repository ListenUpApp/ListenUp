package com.calypsan.listenup.client.presentation.nowplaying

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The playback speeds a cycle control steps through, slowest first.
 *
 * A ring rather than a list with ends: see [nextPlaybackSpeed].
 */
val PLAYBACK_SPEED_STEPS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

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
 * Whether [a] and [b] are the same rate as far as a listener is concerned.
 *
 * ⛔ Not `==`. A speed makes a round trip through storage and the platform player, so 1.5 comes
 * back as 1.4999999 — and every caller that compares one has to survive that. The tolerance is
 * far tighter than the gap between two rungs (0.25 at its narrowest), so this can never report two
 * different presets as the same one.
 */
fun isSamePlaybackSpeed(
    a: Float,
    b: Float,
): Boolean = abs(a - b) < SPEED_MATCH_TOLERANCE

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
 * start. Both the shared `NowPlayingViewModel` and the browser's `PlaybackSession` guard on a
 * non-null timeline before calling.
 */
fun skipTargetMs(
    currentPositionMs: Long,
    seconds: Int,
    speed: Float,
    totalDurationMs: Long,
    forward: Boolean,
): Long {
    val distanceMs = (seconds * speed * 1000).toLong()
    return if (forward) {
        (currentPositionMs + distanceMs).coerceAtMost(totalDurationMs)
    } else {
        (currentPositionMs - distanceMs).coerceAtLeast(0L)
    }
}

/**
 * The speed one press of a cycle control moves to, wrapping from the fastest back to the slowest.
 *
 * The ladder is a ring on purpose: a cycle control with a dead end is a control that stops working,
 * and a listener who overshoots should get back by carrying on rather than by hunting for a reset.
 *
 * The comparison carries a tolerance because a speed makes a round trip through storage and the
 * platform player, so 1.5 comes back as 1.4999999. Matching a rung exactly would read that as
 * "below the ladder" and drop the listener to the bottom of it.
 */
fun nextPlaybackSpeed(current: Float): Float {
    val currentIndex = PLAYBACK_SPEED_STEPS.indexOfFirst { it >= current - SPEED_MATCH_TOLERANCE }
    val nextIndex =
        if (currentIndex == -1 || currentIndex >= PLAYBACK_SPEED_STEPS.lastIndex) 0 else currentIndex + 1
    return PLAYBACK_SPEED_STEPS[nextIndex]
}

/** Slack allowed when matching a speed to a rung — see [isSamePlaybackSpeed]. */
private const val SPEED_MATCH_TOLERANCE = 0.01f
