package com.calypsan.listenup.client.playback

private const val ONE_MINUTE_MS = 60_000L
private const val ONE_HOUR_MS = 3_600_000L
private const val ONE_DAY_MS = 86_400_000L

private const val SHORT_BREAK_REWIND_MS = 5_000L
private const val LONG_BREAK_REWIND_MS = 15_000L
private const val OVERNIGHT_REWIND_MS = 30_000L

/**
 * How far to back up when resuming a book, given how long the listener was away.
 *
 * Coming back to a story mid-sentence is disorienting, and the longer you have been gone the more
 * runway you need to pick the thread back up. The ladder mirrors that: a pause to answer the door
 * costs nothing, a lunch break replays a few seconds, and returning the next day replays enough to
 * re-establish the scene.
 *
 * The result is a playback *start offset*, never a new stored position — see the call site in
 * [PlaybackPreparer]. Rewinding the persisted position would let repeated open/close cycles walk
 * a listener backwards through a book they never un-listened to.
 *
 * @param elapsedAwayMs wall-clock milliseconds since the book was last played.
 * @return milliseconds to subtract from the resume position; never negative.
 */
internal fun autoRewindMs(elapsedAwayMs: Long): Long =
    when {
        elapsedAwayMs < ONE_MINUTE_MS -> 0L
        elapsedAwayMs < ONE_HOUR_MS -> SHORT_BREAK_REWIND_MS
        elapsedAwayMs < ONE_DAY_MS -> LONG_BREAK_REWIND_MS
        else -> OVERNIGHT_REWIND_MS
    }
