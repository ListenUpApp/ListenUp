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

    fun updateSpeed(speed: Float)

    fun reportError(
        message: String,
        isRecoverable: Boolean = false,
    )
}
