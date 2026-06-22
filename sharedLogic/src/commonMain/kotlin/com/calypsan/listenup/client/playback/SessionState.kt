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
 * The full playback session (used by activity-feed `endPlaybackSession` signals)
 * lives on [Active] / [Paused] as `playbackStartPositionMs` + `playbackStartedAt`.
 * It is reset on book finish or new-book takeover.
 */
sealed interface SessionState {
    /** No active playback session. Default state. */
    data object Idle : SessionState

    /** Playing. Full-session tracking lives here. */
    data class Active(
        val bookId: BookId,
        val playbackStartPositionMs: Long,
        val playbackStartedAt: Long,
        val speed: Float,
    ) : SessionState

    /** Paused. Same fields as Active plus pausedAt. */
    data class Paused(
        val bookId: BookId,
        val playbackStartPositionMs: Long,
        val playbackStartedAt: Long,
        val pausedAt: Long,
        val speed: Float,
    ) : SessionState
}
