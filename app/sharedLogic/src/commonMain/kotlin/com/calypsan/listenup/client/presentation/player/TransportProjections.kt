package com.calypsan.listenup.client.presentation.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** The preset playback speeds the speed pickers offer, slowest first. */
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
 * Whether two volume boosts are the same rung of `VolumeBoostLimits.PRESETS_DB`.
 *
 * A tolerance rather than `==` for the same reason [isSamePlaybackSpeed] uses one: the value
 * round-trips through storage and the wire as a float, and a rung that failed to mark itself
 * selected over the last bit of a mantissa would look like the setting had not taken.
 *
 * Shared rather than restated per surface — it was written three times with two different
 * tolerances, which meant a boost could read as selected on one client and not on another.
 */
fun isSameVolumeBoost(
    a: Float,
    b: Float,
): Boolean = abs(a - b) < BOOST_MATCH_TOLERANCE

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
 * How much book a skip of [seconds] covers at [speed]: seconds of listening, so it scales with the rate (spec §3.3).
 * The app and every system skip (notification, lock screen, headset, car) move this far.
 */
fun skipDistanceMs(
    seconds: Int,
    speed: Float,
): Long = (seconds * speed * 1000).toLong()

/**
 * The book position [offsetMs] into [chapter], clamped to the chapter. The browser's Media Session reports and seeks
 * in chapter time (its `seekto` handler is the caller); the player seeks in book time.
 */
fun bookPositionInChapterMs(
    chapter: PlayerChapter,
    offsetMs: Long,
): Long = chapter.startMs + offsetMs.coerceIn(0L, chapter.durationMs)

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

/** Slack allowed when matching a speed to a rung — see [isSamePlaybackSpeed]. */
private const val SPEED_MATCH_TOLERANCE = 0.01f

/** Slack allowed when matching a boost to a rung — see [isSameVolumeBoost]. */
private const val BOOST_MATCH_TOLERANCE = 0.01f
