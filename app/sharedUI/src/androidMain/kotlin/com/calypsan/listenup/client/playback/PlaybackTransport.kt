package com.calypsan.listenup.client.playback

import androidx.media3.common.Player

/**
 * The playback state a session callback needs from the service that owns the players.
 *
 * Deliberately two methods. [ListenUpSessionCallback] handles browse, search, voice and custom
 * commands — none of which own a player — but its transport commands have to act on whichever
 * player is actually producing audio, in the right coordinate space. Naming that dependency as an
 * interface (rather than reaching into the service) is what lets the callback be tested with a
 * fake, which the `inner class` it replaced could not be.
 */
internal interface PlaybackTransport {
    /**
     * The player actually producing audio right now — the cast player while casting, otherwise the
     * raw local ExoPlayer.
     *
     * Never the session player: that is [ChapterWindowPlayer], a chapter-scoped presentation
     * wrapper whose indices and positions are chapter-relative. Transport commands resolved
     * against it would silently compute in the wrong coordinate space.
     */
    fun activeTransportPlayer(): Player?

    /**
     * Current position in book-relative coordinates — the sum of prior file durations plus the
     * offset into the current file, not the file-relative `player.currentPosition`.
     */
    fun bookRelativePositionMs(): Long

    /**
     * Applies a resume playback speed to the local player.
     *
     * Deliberately the local player rather than [activeTransportPlayer] — resume speed is a
     * local-playback concern, and preserving that distinction is why this rides the seam instead
     * of being reimplemented against the transport player.
     */
    fun applyResumeSpeed(speed: Float)
}
