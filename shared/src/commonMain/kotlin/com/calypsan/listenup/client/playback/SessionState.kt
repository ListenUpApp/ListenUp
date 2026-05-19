package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId

/**
 * Reactive session state for [ProgressTracker].
 *
 * Replaces the pre-Phase-C nullable `currentSession: ListeningSession?` and
 * `playbackSessionStart: PlaybackSessionStart?` mutable vars with a single
 * sealed hierarchy exposed as `StateFlow<SessionState>`. Makes illegal states
 * unrepresentable: a book is either Idle (no playback in flight), Active
 * (playing), or Paused.
 *
 * The chunked listening-event session (the 30-second-flush window) lives on
 * [Active] / [Paused] as `chunkStartPositionMs` + `chunkStartedAt`. The full
 * playback session (used by activity-feed `endPlaybackSession` signals) lives
 * inline as `playbackStartPositionMs` + `playbackStartedAt`. Both are reset
 * together on book finish or new-book takeover.
 */
sealed interface SessionState {
    /** No active playback session. Default state. */
    data object Idle : SessionState

    /** Playing. Both chunked and full-session tracking live here. */
    data class Active(
        val bookId: BookId,
        val chunkStartPositionMs: Long,
        val chunkStartedAt: Long,
        val playbackStartPositionMs: Long,
        val playbackStartedAt: Long,
        val speed: Float,
    ) : SessionState

    /** Paused. Same fields as Active plus pausedAt. */
    data class Paused(
        val bookId: BookId,
        val chunkStartPositionMs: Long,
        val chunkStartedAt: Long,
        val playbackStartPositionMs: Long,
        val playbackStartedAt: Long,
        val pausedAt: Long,
        val speed: Float,
    ) : SessionState
}
