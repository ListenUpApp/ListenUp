package com.calypsan.listenup.client.domain.repository

/**
 * Sealed hierarchy describing every distinct write pattern that can mutate a
 * book's playback position. Funnels every write — player events, user commands,
 * cross-device SSE merges — through a single repository entry point
 * ([PlaybackPositionRepository.savePlaybackState]).
 *
 * The eight player-event variants describe distinct semantic
 * intents even when their persistence shapes overlap (e.g., Position /
 * PlaybackPaused / PeriodicUpdate all save position+speed but represent
 * different events). Three additional variants (MarkComplete / DiscardProgress /
 * Restart) describe user commands from BookDetail menu actions.
 *
 * Single-writer ownership rule: every mutation of `playback_positions` MUST
 * route through one of these variants. Adding a new write pattern means adding
 * a new variant — the sealed `when` exhaustiveness compile-error reminds every
 * consumer to handle it.
 */
sealed interface PlaybackUpdate {
    /** Periodic position save (no session-state side effects). */
    data class Position(
        val positionMs: Long,
        val speed: Float,
    ) : PlaybackUpdate

    /** Explicit user-driven speed change. Sets hasCustomSpeed = [custom]. */
    data class Speed(
        val positionMs: Long,
        val speed: Float,
        val custom: Boolean,
    ) : PlaybackUpdate

    /** User reset to global default speed. Sets hasCustomSpeed = false. */
    data class SpeedReset(
        val positionMs: Long,
        val defaultSpeed: Float,
    ) : PlaybackUpdate

    /** Player started playback. Records startedAt if currently null. */
    data class PlaybackStarted(
        val positionMs: Long,
        val speed: Float,
    ) : PlaybackUpdate

    /** Player paused. */
    data class PlaybackPaused(
        val positionMs: Long,
        val speed: Float,
    ) : PlaybackUpdate

    /** 30s periodic flush during active playback. */
    data class PeriodicUpdate(
        val positionMs: Long,
        val speed: Float,
    ) : PlaybackUpdate

    /** Player reached end of book. Sets isFinished = true; queues MarkComplete pending-op. */
    data class BookFinished(
        val finalPositionMs: Long,
    ) : PlaybackUpdate

    /** User command: mark complete from BookDetail menu. */
    data class MarkComplete(
        val startedAt: Long?,
        val finishedAt: Long?,
    ) : PlaybackUpdate

    /** User command: discard progress (reset to 0 + isFinished=false). */
    data object DiscardProgress : PlaybackUpdate

    /** User command: restart book (position=0, isFinished=false; resets startedAt to now — new reading session). */
    data object Restart : PlaybackUpdate
}
