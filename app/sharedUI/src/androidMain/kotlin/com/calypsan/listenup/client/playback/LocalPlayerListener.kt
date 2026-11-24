package com.calypsan.listenup.client.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Listens to local [ExoPlayer] events for logging, progress tracking, refusal notifications,
 * error recovery, Wi-Fi lock updates, and seek-split listening spans.
 */
@OptIn(UnstableApi::class)
internal class LocalPlayerListener(
    private val localPlayer: () -> ExoPlayer?,
    private val serviceScope: CoroutineScope,
    private val bookkeeper: PlaybackBookkeeper,
    private val refusalTracker: PlaybackRefusalTracker,
    private val refusalNotifier: PlaybackRefusalNotifier,
    private val errorBus: ErrorBus,
    private val errorHandler: PlaybackErrorHandler,
    private val progressTracker: ProgressTracker,
    private val playbackManager: PlaybackManager,
    private val listeningEventRecorder: ListeningEventRecorder,
) : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
        val stateName =
            when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
        logger.debug { "Playback state: $stateName" }

        when (playbackState) {
            Player.STATE_ENDED -> {
                // Book finished - longer grace period. The near-end guard lives in the tracker:
                // pass where the listener actually is, so an ENDED reported on release or stop
                // cannot mark a half-read book finished.
                bookkeeper.currentBookId?.let { bookId ->
                    progressTracker.onBookFinished(
                        bookId,
                        positionMs = bookkeeper.bookRelativePositionMs(),
                        durationMs = bookkeeper.bookDurationMs(),
                    )
                }
                bookkeeper.startIdleTimer(PlaybackBookkeeper.IDLE_TIMEOUT_LONG, "book_finished")
            }

            Player.STATE_IDLE -> {
                // Player cleared
                bookkeeper.startIdleTimer(PlaybackBookkeeper.IDLE_TIMEOUT_SHORT, "idle")
            }
        }
    }

    /**
     * Reports a play request the platform refused outright, as opposed to one interrupted
     * partway. See [isPlaybackRefused] for why the two need telling apart, and
     * [PlaybackRefusalTracker] for the state that separates them across a whole sequence.
     */
    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        if (!refusalTracker.onPlayWhenReadyChanged(playWhenReady, reason)) return

        logger.warn {
            "Playback refused: the platform denied audio focus for a play we just requested. " +
                "Check `adb shell dumpsys audio` for the AudioHardening entry."
        }
        val refusal =
            PlaybackError.BlockedInBackground(
                debugInfo = "playWhenReady=false reason=AUDIO_FOCUS_LOSS before playback started",
            )
        // The bus reaches the UI when the app is open; the notification is the path that
        // reaches a listener staring at a lock-screen button that did nothing. Both are
        // wanted — a refusal is now known to happen with the app plainly on screen.
        errorBus.emit(refusal)
        refusalNotifier.notifyRefused(refusal)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) =
        bookkeeper.handleIsPlayingChanged(TransportSource.LOCAL, isPlaying)

    override fun onPlayerError(error: PlaybackException) {
        logger.error(error) { "Playback error: ${error.message ?: "no message"}" }

        // Surface stuck-player as a typed PlaybackError.Stalled so the global
        // error bus and UI can offer a retry affordance.
        if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) {
            errorBus.emit(PlaybackError.Stalled(debugInfo = error.message))
        }

        // Captured synchronously: see playerErrorActionFor's KDoc for why reading the
        // player inside the coroutine was a crash.
        val activePlayer = localPlayer()
        if (playerErrorActionFor(hasPlayer = activePlayer != null) ==
            PlayerErrorAction.NOTHING_TO_RECOVER ||
            activePlayer == null
        ) {
            logger.warn { "Playback error with no attached player — nothing to recover" }
            return
        }
        val classified = errorHandler.classify(error)

        serviceScope.launch {
            val handled =
                errorHandler.handle(
                    error = classified,
                    player = activePlayer,
                    currentBookId = bookkeeper.currentBookId,
                    // Book-relative (sum of prior file durations + file offset); never the
                    // raw file-relative player.currentPosition read inside the handler.
                    bookPositionMs = bookkeeper.bookRelativePositionMs(),
                    bookDurationMs = bookkeeper.bookDurationMs(),
                    onShowError = { appError -> playbackManager.reportError(appError) },
                )

            if (!handled) {
                // Error couldn't be recovered
                bookkeeper.startIdleTimer(PlaybackBookkeeper.IDLE_TIMEOUT_SHORT, "error")
            }
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        logger.debug { "Media item transition: ${mediaItem?.mediaId ?: "none"}, reason: $reason" }
    }

    /** A new playlist may stream or be downloaded; hold Wi-Fi awake only when it streams. */
    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        val exoPlayer = localPlayer() ?: return
        val uris =
            (0 until exoPlayer.mediaItemCount).mapNotNull { exoPlayer.getMediaItemAt(it).localConfiguration?.uri }
        exoPlayer.setWakeMode(wakeModeFor(uris))
    }

    /**
     * A user seek splits the listening span: finalize the pre-seek span at the old book-relative
     * position and open a fresh one at the new position (see [ListeningEventRecorder.onSeek]).
     * Without this a seek would leave the open span to be extended across the jump on the next
     * heartbeat, fabricating content coverage (e.g. a seek from 0:12:00 to 5:00:00 would count
     * the whole ~5 h as listened) that corrupts the books-finished / coverage-derived stats.
     *
     * Only [Player.DISCONTINUITY_REASON_SEEK] discontinuities are user seeks; auto transitions
     * and period boundaries are ignored. Positions are converted to book-relative via the active
     * [PlaybackManager.currentTimeline]; the raw file-relative [Player.PositionInfo.positionMs]
     * would misplace the split on a multi-file book.
     */
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        bookkeeper.currentBookId ?: return
        val timeline = playbackManager.currentTimeline.value
        val beforeMs =
            timeline?.toBookPosition(oldPosition.mediaItemIndex, oldPosition.positionMs)
                ?: oldPosition.positionMs
        val afterMs =
            timeline?.toBookPosition(newPosition.mediaItemIndex, newPosition.positionMs)
                ?: newPosition.positionMs
        logger.debug { "Seek discontinuity: $beforeMs -> $afterMs (book-relative)" }
        serviceScope.launch {
            listeningEventRecorder.onSeek(positionBeforeSeek = beforeMs, positionAfterSeek = afterMs)
        }
    }
}
