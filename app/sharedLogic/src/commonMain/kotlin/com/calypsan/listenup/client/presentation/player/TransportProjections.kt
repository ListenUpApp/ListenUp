package com.calypsan.listenup.client.presentation.player

import kotlin.math.abs

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
 * The book position [offsetMs] into [chapter], clamped to the chapter. The browser's Media Session reports and seeks
 * in chapter time (its `seekto` handler is the caller); the player seeks in book time.
 */
fun bookPositionInChapterMs(
    chapter: PlayerChapter,
    offsetMs: Long,
): Long = chapter.startMs + offsetMs.coerceIn(0L, chapter.durationMs)

/** Slack allowed when matching a speed to a rung — see [isSamePlaybackSpeed]. */
private const val SPEED_MATCH_TOLERANCE = 0.01f

/** Slack allowed when matching a boost to a rung — see [isSameVolumeBoost]. */
private const val BOOST_MATCH_TOLERANCE = 0.01f
