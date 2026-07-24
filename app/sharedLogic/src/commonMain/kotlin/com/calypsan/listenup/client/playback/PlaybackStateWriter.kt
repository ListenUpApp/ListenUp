package com.calypsan.listenup.client.playback

/**
 * Narrow interface for platform-specific playback event sources (e.g., Android's
 * MediaControllerHolder Player.Listener) to push state changes into PlaybackManager
 * without taking a full dependency on the concrete class.
 *
 * Implemented by [PlaybackManager].
 */
interface PlaybackStateWriter {
    fun setPlaying(playing: Boolean)

    fun setBuffering(buffering: Boolean)

    fun setPlaybackState(state: PlaybackState)

    fun updatePosition(positionMs: Long)

    /**
     * Update position from ExoPlayer's per-file (media-item) coordinates.
     *
     * Media3 builds one media item per audio file, so `player.currentPosition` is
     * position *within the current file*. For a multi-file book that is NOT the
     * book-relative position — file 8 at 12 min is ~9 h into the book, not 12 min.
     * Passing the raw file offset to [updatePosition] would corrupt `currentPositionMs`,
     * regressing every persisted position and breaking skip/seek math on Android.
     *
     * The implementation converts to a book-relative position using the active
     * [PlaybackTimeline] (which owns the cumulative per-file offsets) and then delegates
     * to [updatePosition]. Single-file `.m4b` books convert identically (index 0,
     * file == book), so this is a no-op harm for them. Android's `MediaControllerHolder`
     * polling loop calls this; Desktop/iOS already push book-relative positions through
     * [updatePosition] directly.
     *
     * @param mediaItemIndex Index of the current media item in the ExoPlayer playlist.
     * @param positionInItemMs Position within that file, in milliseconds.
     */
    fun updatePositionFromMediaItem(
        mediaItemIndex: Int,
        positionInItemMs: Long,
    )

    fun updateSpeed(speed: Float)

    fun reportError(
        message: String,
        isRecoverable: Boolean = false,
    )
}
