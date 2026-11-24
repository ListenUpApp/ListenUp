package com.calypsan.listenup.client.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.calypsan.listenup.client.automotive.CustomActions
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Dispatches custom session commands (skip back/forward, chapter prev/next, speed cycle,
 * book-relative seek) against the active [PlaybackTransport] player.
 */
internal class SessionCustomCommandHandler(
    private val playbackManager: PlaybackManager,
    private val transport: PlaybackTransport,
    private val skipIntervals: SkipIntervalsHolder,
) {
    fun handle(
        customCommand: SessionCommand,
        args: Bundle,
    ): SessionResult {
        // Route transport controls to the TRANSPORT player — the cast player while casting,
        // the raw local ExoPlayer otherwise — so skip/chapter/speed reach the receiver, not
        // the paused local player, and so the seek math below (which resolves book-relative
        // positions via `timeline`) never runs against ChapterWindowPlayer's chapter-relative
        // coordinates. Queue order is identical on both (the index-shift guard in
        // CastHandoffCoordinator.handoffToCast ensures a 1:1 map), so the timeline's
        // book-relative → index/offset math holds unchanged.
        val p =
            transport.activeTransportPlayer() ?: return SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
        val chapters = playbackManager.chapters.value
        val timeline = playbackManager.currentTimeline.value

        when (customCommand.customAction) {
            AudiobookNotificationProvider.COMMAND_SKIP_BACK -> {
                // Book-relative position, less the interval in seconds of listening (× speed), then seek.
                val backwardMs = skipDistanceMs(skipIntervals.backwardSec, p.playbackParameters.speed)
                val currentBookPosition = transport.bookRelativePositionMs()
                val newPosition = (currentBookPosition - backwardMs).coerceAtLeast(0)

                // Resolve new position to mediaItemIndex and filePosition
                if (timeline != null) {
                    val resolved = timeline.resolve(newPosition)
                    playbackManager.seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                } else {
                    // Fallback to simple seek within current file
                    val newFilePosition = (p.currentPosition - backwardMs).coerceAtLeast(0)
                    playbackManager.seekAndPublish(p, newFilePosition)
                }
                logger.debug { "Skip back ${backwardMs}ms: $currentBookPosition -> $newPosition" }
            }

            AudiobookNotificationProvider.COMMAND_SKIP_FORWARD -> {
                val forwardMs = skipDistanceMs(skipIntervals.forwardSec, p.playbackParameters.speed)
                val currentBookPosition = transport.bookRelativePositionMs()
                val maxPosition = timeline?.totalDurationMs ?: p.duration
                val newPosition = (currentBookPosition + forwardMs).coerceAtMost(maxPosition)

                if (timeline != null) {
                    val resolved = timeline.resolve(newPosition)
                    playbackManager.seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                } else {
                    val newFilePosition = (p.currentPosition + forwardMs).coerceAtMost(p.duration)
                    playbackManager.seekAndPublish(p, newFilePosition)
                }
                logger.debug { "Skip forward ${forwardMs}ms: $currentBookPosition -> $newPosition" }
            }

            AudiobookNotificationProvider.COMMAND_PREV_CHAPTER -> {
                // The shared ChapterNavigation rule, as ChapterWindowPlayer.handleSeek's
                // COMMAND_SEEK_TO_PREVIOUS branch and every other surface use it.
                val target = ChapterNavigation.previousStartMs(transport.bookRelativePositionMs(), chapters)
                if (timeline != null && target != null) {
                    val resolved = timeline.resolve(target)
                    playbackManager.seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                    logger.debug { "Previous chapter target: ${target}ms" }
                } else {
                    // Honest-over-silent: no chapter data means nothing happened. Telling the head
                    // unit RESULT_SUCCESS here would be a lie — it actively tells the car the
                    // command worked when the player never moved.
                    logger.debug { "Previous chapter requested with no chapter data available — skipping" }
                    return SessionResult(SessionResult.RESULT_INFO_SKIPPED)
                }
            }

            AudiobookNotificationProvider.COMMAND_NEXT_CHAPTER -> {
                // The shared ChapterNavigation rule, as ChapterWindowPlayer.handleSeek's COMMAND_SEEK_TO_NEXT
                // branch uses it. At the last chapter there is no next: nothing moves, and the head unit is
                // told so rather than told it worked.
                val target = ChapterNavigation.nextStartMs(transport.bookRelativePositionMs(), chapters)
                if (timeline != null && target != null) {
                    val resolved = timeline.resolve(target)
                    playbackManager.seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                    logger.debug { "Next chapter target: ${target}ms" }
                } else {
                    logger.debug { "Next chapter requested at the last chapter or with no chapter data — skipping" }
                    return SessionResult(SessionResult.RESULT_INFO_SKIPPED)
                }
            }

            CustomActions.CYCLE_SPEED -> {
                val currentSpeed = p.playbackParameters.speed
                val newSpeed = PlaybackPresets.nextSpeedInCycle(currentSpeed)
                p.setPlaybackSpeed(newSpeed)
                playbackManager.onSpeedChanged(newSpeed)
                logger.info {
                    "Speed cycled: $currentSpeed -> $newSpeed"
                }
            }

            CustomActions.SEEK_TO_BOOK_POSITION -> {
                // The in-app seek transport (see AndroidPlaybackController.seekTo's KDoc):
                // book-relative because the session player is ChapterWindowPlayer, and a
                // controller-side seek would be reinterpreted chapter-relatively and clamped
                // to the current chapter window.
                val target = args.getLong(CustomActions.EXTRA_BOOK_POSITION_MS, -1L)
                if (target < 0) {
                    return SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                }
                if (timeline != null) {
                    val resolved = timeline.resolve(target.coerceIn(0L, timeline.totalDurationMs))
                    playbackManager.seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                } else {
                    playbackManager.seekAndPublish(p, target)
                }
                logger.debug { "Seek to book position: ${target}ms" }
            }
        }

        return SessionResult(SessionResult.RESULT_SUCCESS)
    }
}

/**
 * Seeks the transport player and publishes where it landed.
 *
 * Publishing is not optional bookkeeping. The in-app position is fed by a poll that runs
 * only while audio is playing, so a notification skip taken while paused moved the player
 * and left [PlaybackManager] holding the old position — and the next in-app skip, computed
 * from that stale value, silently undid the one the listener just made.
 *
 * The landing coordinates are the ones just resolved rather than read back from the player,
 * so the published position cannot drift from the seek that produced it.
 */
private fun PlaybackManager.seekAndPublish(
    player: Player,
    mediaItemIndex: Int,
    positionInFileMs: Long,
) {
    player.seekTo(mediaItemIndex, positionInFileMs)
    updatePositionFromMediaItem(mediaItemIndex, positionInFileMs)
}

/**
 * The file-relative fallback taken when no [PlaybackTimeline] is loaded: the seek stays
 * within the current media item, so that item's index is the one to publish against.
 */
private fun PlaybackManager.seekAndPublish(
    player: Player,
    positionInFileMs: Long,
) {
    player.seekTo(positionInFileMs)
    updatePositionFromMediaItem(player.currentMediaItemIndex, positionInFileMs)
}
