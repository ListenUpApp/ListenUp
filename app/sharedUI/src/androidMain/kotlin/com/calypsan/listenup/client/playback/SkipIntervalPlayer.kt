package com.calypsan.listenup.client.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Makes `COMMAND_SEEK_BACK`/`COMMAND_SEEK_FORWARD` honour the user's configured skip intervals.
 *
 * Those two commands are what a car's steering-wheel skip buttons, a Wear tile and a Bluetooth
 * remote invoke, and ExoPlayer takes their increments from
 * `ExoPlayer.Builder.setSeek{Back,Forward}IncrementMs` — a **builder-only** setting with no
 * runtime setter. Honouring a Settings change through the builder would mean rebuilding the
 * player, which tears down the audio session mid-sentence. So the increments are intercepted
 * here instead: [forwardMs]/[backwardMs] are read on *every* call, so a change lands on the very
 * next press with the same player still sounding.
 *
 * Both the *behaviour* and the *reported* increments are overridden. `ForwardingSimpleBasePlayer`
 * copies `getSeekBackIncrement()`/`getSeekForwardIncrement()` into the state every connected
 * controller reads, so overriding only the seek would leave a head unit rendering "30" while the
 * press moved 45 seconds.
 *
 * The seek arithmetic is deliberately identical to `BasePlayer.seekToOffset` — current position
 * plus the offset, clamped to the media's duration and to zero — so this changes the increment
 * and nothing else about what a skip means. In particular it stays *file*-relative, exactly as
 * ExoPlayer's own implementation is; the book-relative skips (the in-app buttons and the
 * notification's custom commands) resolve through `PlaybackTimeline` on their own paths.
 *
 * Wrapped by [ChapterWindowPlayer] rather than wrapping it: this operates in the underlying
 * player's coordinates, and `ForwardingSimpleBasePlayer.handleSeek` routes both commands
 * straight down to [seekBack]/[seekForward].
 *
 * @param player The local ExoPlayer instance to wrap.
 * @param forwardMs The currently-configured forward skip, in milliseconds. Read per call.
 * @param backwardMs The currently-configured backward skip, in milliseconds. Read per call.
 */
class SkipIntervalPlayer(
    player: Player,
    private val forwardMs: () -> Long,
    private val backwardMs: () -> Long,
) : ForwardingPlayer(player) {
    override fun getSeekBackIncrement(): Long = backwardMs()

    override fun getSeekForwardIncrement(): Long = forwardMs()

    override fun seekBack() = seekToOffset(-backwardMs())

    override fun seekForward() = seekToOffset(forwardMs())

    /** `BasePlayer.seekToOffset`, re-expressed over the configured increment. */
    private fun seekToOffset(offsetMs: Long) {
        val durationMs = duration
        var positionMs = currentPosition + offsetMs
        if (durationMs != C.TIME_UNSET) {
            positionMs = minOf(positionMs, durationMs)
        }
        seekTo(maxOf(positionMs, 0L))
    }
}
