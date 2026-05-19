package com.calypsan.listenup.client.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic audio player interface.
 *
 * Implementations handle actual audio output (ExoPlayer on Android, JavaFX on Desktop).
 * PlaybackManager drives this after timeline preparation.
 * All positions are book-relative (translated internally to segment coordinates).
 */
interface AudioPlayer {
    /** Current playback state. */
    val state: StateFlow<PlaybackState>

    /** Current position in book timeline (milliseconds). */
    val positionMs: StateFlow<Long>

    /** Total duration of loaded content (milliseconds). */
    val durationMs: StateFlow<Long>

    /**
     * Load audio segments for playback.
     * Prepares the first segment but does not start playback.
     */
    suspend fun load(segments: List<AudioSegment>)

    /** Start or resume playback. */
    fun play()

    /** Pause playback. */
    fun pause()

    /**
     * Seek to a book-relative position.
     * Internally translates to the correct segment and offset.
     */
    fun seekTo(positionMs: Long)

    /** Set playback speed multiplier. */
    fun setSpeed(speed: Float)

    /** Release all resources. Call when playback session ends. */
    fun release()
}

/**
 * Represents a single audio file segment in the playback timeline.
 *
 * @property url Streaming URL for this segment (with auth via token provider)
 * @property localPath Local file path if downloaded (preferred over streaming)
 * @property durationMs Duration of this segment in milliseconds
 * @property offsetMs Where this segment starts in the book timeline
 */
data class AudioSegment(
    val url: String,
    val localPath: String?,
    val durationMs: Long,
    val offsetMs: Long,
)

/**
 * Playback state reported by the audio player.
 *
 * Sealed hierarchy so [Error] can carry a platform-specific diagnostic message.
 * The five non-error variants are [data object]s with structural equality, so
 * existing `state == PlaybackState.Idle` etc. continue to work unchanged.
 */
sealed interface PlaybackState {
    /** No content loaded. */
    data object Idle : PlaybackState

    /** Content is loading/buffering. */
    data object Buffering : PlaybackState

    /** Actively playing audio. */
    data object Playing : PlaybackState

    /** Playback is paused. */
    data object Paused : PlaybackState

    /** Playback completed (reached end of all segments). */
    data object Ended : PlaybackState

    /**
     * An error occurred during playback.
     *
     * @property message Optional platform-specific diagnostic (e.g., "Check GStreamer installation.").
     *                    PlaybackManager falls back to a generic string when null.
     * @property isRecoverable Hint to UI whether retry might succeed.
     */
    data class Error(
        val message: String? = null,
        val isRecoverable: Boolean = false,
    ) : PlaybackState
}
