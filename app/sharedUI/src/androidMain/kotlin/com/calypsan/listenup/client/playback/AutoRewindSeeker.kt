package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Applies the in-session pause→resume rewind (#1220/#1237) to the player that is actually
 * producing audio.
 *
 * [PlaybackProgressReporter] owns the decision — how long the listener was away, and which rung of
 * the [autoRewindMs] ladder that earns. This owns the actuation, and specifically owns *which
 * player the seek is aimed at*, which is the part that has been wrong in production.
 *
 * ## Why this is a class and not four lines in `PlaybackService.onCreate`
 *
 * It was four lines in `PlaybackService.onCreate`, and it reached for
 * `mediaLibrarySession?.player`. That was correct when #1237 wrote it — the session player was the
 * raw local player. #1241 then made the session player [ChapterWindowPlayer], a chapter-scoped
 * presentation wrapper that reads incoming seeks as chapter-relative and clamps them to the current
 * chapter. From that commit onwards the actuator handed BOOK coordinates to a chapter-relative
 * player, so every resume after a pause of a minute or more jumped the listener to the next chapter
 * boundary instead of stepping back five seconds.
 *
 * The invariant that would have prevented it was already written down — [PlaybackTransport]'s KDoc
 * says transport commands must resolve against [PlaybackTransport.activeTransportPlayer], never the
 * session player — but a rule that lives in prose is enforced only by whoever is reading. Holding a
 * [PlaybackTransport] instead of the service makes the wrong player unreachable: there is no
 * session player in scope to aim at. That is the actual fix; the seek arithmetic is unchanged.
 *
 * @property transport The seam exposing the raw transport player and the book-relative position.
 * @property timelineProvider Snapshot of the playing book's file/offset timeline, or null before
 *   playback has been prepared.
 */
internal class AutoRewindSeeker(
    private val transport: PlaybackTransport,
    private val timelineProvider: () -> PlaybackTimeline?,
) {
    /**
     * Steps playback back by [rewindMs] and returns the new BOOK-relative position, or null when
     * no player is available to seek (nothing is playing, so there is nothing to rewind).
     *
     * The returned value is the caller's cue to update [PlaybackManager]'s position — reported
     * rather than written here so this class stays a pure actuator over the transport seam.
     */
    fun seekBack(rewindMs: Long): Long? {
        val player = transport.activeTransportPlayer() ?: return null
        val timeline = timelineProvider()
        val newPositionMs = (transport.bookRelativePositionMs() - rewindMs).coerceAtLeast(0L)

        if (timeline == null) {
            // No timeline yet, so book coordinates cannot be resolved. Fall back to a
            // file-relative step within the current item — the same degradation
            // `onCustomCommand`'s skip-back uses, and correct within a single file.
            player.seekTo((player.currentPosition - rewindMs).coerceAtLeast(0L))
        } else {
            val resolved = timeline.resolve(newPositionMs)
            player.seekTo(resolved.mediaItemIndex, resolved.positionInFileMs)
        }

        logger.debug { "Auto-rewind on resume: backed up ${rewindMs}ms to ${newPositionMs}ms" }
        return newPositionMs
    }
}
