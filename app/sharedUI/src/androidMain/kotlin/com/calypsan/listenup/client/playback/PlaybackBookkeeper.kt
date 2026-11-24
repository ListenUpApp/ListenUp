package com.calypsan.listenup.client.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Service surface needed by [PlaybackBookkeeper] to read transport coordinates and drive
 * idle player release without holding a direct reference to [PlaybackService].
 */
internal interface PlaybackBookkeeperHost {
    fun activeTransportPlayer(): Player?

    fun localPlayer(): ExoPlayer?

    fun announceIdleRelease()

    fun stopService()
}

/**
 * Owns progress persistence, listening-span open/close, R128 gain tracking, position polling loops,
 * and tiered idle timeouts for [PlaybackService].
 */
@OptIn(UnstableApi::class)
internal class PlaybackBookkeeper(
    private val host: PlaybackBookkeeperHost,
    private val serviceScope: CoroutineScope,
    private val transport: ActiveTransport,
    private val gainProcessor: GainAudioProcessor,
    private val playbackManager: PlaybackManager,
    private val playbackStateWriter: PlaybackStateWriter,
    private val progressTracker: ProgressTracker,
    private val listeningEventRecorder: ListeningEventRecorder,
    private val sleepTimerManager: SleepTimerManager,
    private val refusalNotifier: PlaybackRefusalNotifier,
) {
    /**
     * The measurement last handed to [ProgressTracker], so an unmoved reading is not re-saved.
     * Cleared on every book change alongside [GainAudioProcessor.beginBook].
     */
    private var lastSavedMeasuredGainDb: Float? = null

    /**
     * The book [gainProcessor]'s meter is currently measuring — set at the same instant the meter
     * is reset, so it is the one thing that knows what a reading actually describes.
     */
    private var meterBookId: BookId? = null

    private var idleJob: Job? = null
    private var positionUpdateJob: Job? = null
    private var uiPositionJob: Job? = null

    /**
     * The book every position this service saves is filed under: PlaybackManager's, but only while the
     * transport holds that book's media ([positionOwner]). Null in the moment after a switch when the player
     * still holds the previous book, so its place is never saved as the new one's.
     */
    val currentBookId: BookId?
        get() =
            positionOwner(
                activeBookId = playbackManager.currentBookId.value,
                timeline = playbackManager.currentTimeline.value,
                transportMediaId = host.activeTransportPlayer()?.currentMediaItem?.mediaId,
            )

    /**
     * Get the current book-relative position.
     *
     * ExoPlayer tracks position within the current file, but we need position
     * relative to the entire book for progress tracking. The PlaybackTimeline
     * handles this translation.
     *
     * Reads the active transport player — the local ExoPlayer normally, the cast
     * player while casting — so position recording follows wherever audio is playing.
     *
     * @return Book-relative position in milliseconds, or 0 if unavailable
     */
    fun bookRelativePositionMs(): Long {
        val player = host.activeTransportPlayer() ?: return 0L
        val timeline = playbackManager.currentTimeline.value ?: return player.currentPosition
        return timeline.toBookPosition(player.currentMediaItemIndex, player.currentPosition)
    }

    /**
     * The whole book's duration in milliseconds: the timeline's total, else the player's own
     * duration when it knows it (`C.TIME_UNSET` is negative), else 0 — which the tracker reads as
     * "unknown" and never treats as finished.
     */
    fun bookDurationMs(): Long =
        playbackManager.currentTimeline.value?.totalDurationMs
            ?: host.activeTransportPlayer()?.run { duration.takeIf { it > 0 } }
            ?: 0L

    /**
     * Wire the gain stage to the two signals that drive it: the gain to apply, and the book
     * boundary that scopes a loudness measurement.
     *
     * Both ride [PlaybackManager] rather than the media-item plumbing because `currentBookId` is
     * already this service's notion of which book is loaded — the same source the pause funnel
     * saves against.
     *
     * The reset is asynchronous, though, so it lands some time after the flow has already flipped
     * to the new book. [meterBookId] is stamped inside that resumption, which is what lets
     * [saveRefinedMeasurement] tell a reading of the new book from a leftover reading of the old.
     */
    fun observeGain() {
        serviceScope.launch {
            playbackManager.effectiveGainDb.collect { gainProcessor.setGainDb(it) }
        }
        serviceScope.launch {
            // A StateFlow conflates and drops duplicates, so this fires once per real book change.
            // Loudness integrates over one book, never across two.
            playbackManager.currentBookId.collect { bookId ->
                gainProcessor.beginBook()
                meterBookId = bookId
                lastSavedMeasuredGainDb = null
            }
        }
    }

    fun saveCurrentPosition() {
        val player = host.localPlayer() ?: return
        val bookId = currentBookId ?: return

        progressTracker.onPlaybackPaused(
            bookId = bookId,
            positionMs = bookRelativePositionMs(),
            speed = player.playbackParameters.speed,
            durationMs = bookDurationMs(),
        )
    }

    /**
     * Persist a refined R128 measurement at a pause, if it has actually moved.
     *
     * Hung off the pause funnel so no pause path can forget it. Deliberately does NOT re-apply the
     * gain: the measurement refines continuously while a book plays, and reacting to it live would
     * be an audible level jump mid-sentence. A new value takes effect at the next load, or the next
     * time the user moves the boost.
     */
    private fun saveRefinedMeasurement(
        bookId: BookId,
        positionMs: Long,
    ) {
        val measured = gainProcessor.measuredGainDb() ?: return
        // [meterBookId], not currentBookId: the latter is the very source [bookId] was read from a
        // moment ago on this same thread, so comparing them can never fire. What can go wrong is a
        // pause landing after the flow has flipped to the next book but before the reset in
        // [observeGain] has run — the meter is still integrating the outgoing book, and this would
        // file its loudness under the incoming book's id.
        val worthSaving =
            shouldSaveMeasurement(
                measured = measured,
                lastSaved = lastSavedMeasuredGainDb,
                meterBookId = meterBookId,
                bookId = bookId,
            )
        if (!worthSaving) return
        progressTracker.onMeasuredGain(bookId = bookId, positionMs = positionMs, gainDb = measured)
        lastSavedMeasuredGainDb = measured
    }

    /**
     * Durable position save for Android lifecycle teardown callbacks.
     *
     * [PlaybackService.onDestroy] and [PlaybackService.onTaskRemoved] are synchronous Android
     * callbacks that cannot suspend. [saveCurrentPosition] uses [ProgressTracker.onPlaybackPaused],
     * which is fire-and-forget (`scope.launch { }`): the launched coroutine races
     * `serviceScope.cancel()` in `onDestroy` and races OS process death in `onTaskRemoved`, so
     * the Room write may never run.
     *
     * This function uses [runBlocking] with [NonCancellable] to ensure the write
     * completes before the callback returns. [runBlocking] is intentional and correct
     * here — this is a platform-boundary use where suspension is impossible, not the
     * anti-pattern banned in coroutine-path code.
     */
    fun saveCurrentPositionBlocking() {
        val bookId = currentBookId ?: return
        val positionMs = bookRelativePositionMs()
        runBlocking(NonCancellable) {
            progressTracker.savePositionNow(bookId, positionMs, bookDurationMs())
        }
    }

    fun startIdleTimer(
        timeout: Duration,
        reason: String,
    ) {
        // Never idle-out while casting: the cast player lives inside THIS service's session,
        // and its play/pause events never reach the local PlayerListener that would cancel the
        // timer — so a timer started here (e.g. by the pause that hand-off triggers on the local
        // player) would tear the cast session down after the timeout. The flag check is the
        // robust guard because onIsPlayingChanged is posted asynchronously and could fire after
        // a bare cancel. Covers every startIdleTimer path (pause, idle, book-finished, error).
        if (transport.casting) return
        idleJob?.cancel()
        idleJob =
            serviceScope.launch {
                logger.debug { "Idle timer started: $timeout ($reason)" }
                delay(timeout)
                logger.info { "Idle timeout reached, stopping service ($reason)" }
                // Not casting (guarded above): the local player is the transport. The app's controller keeps the
                // service bound, so stopSelf() alone would leave the decoder and notification up.
                host.localPlayer()?.let { local ->
                    releaseIdlePlayer(local, ::saveCurrentPosition, host::announceIdleRelease)
                } ?: saveCurrentPosition()
                host.stopService()
            }
    }

    fun cancelIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob =
            serviceScope.launch {
                while (isActive) {
                    delay(POSITION_UPDATE_INTERVAL)
                    // Gate + read off the TRANSPORT player — the cast player while casting, the
                    // local ExoPlayer otherwise. Never the session player: once ChapterWindowPlayer
                    // presents the session, its positions are chapter-relative and would corrupt
                    // this data. The local player is paused during a cast (its audio focus is
                    // released), so gating on player.isPlaying froze all periodic persistence for
                    // the whole cast session: position stopped propagating to other devices and a
                    // battery-death mid-cast lost the session + dropped the span.
                    // bookRelativePositionMs() already reads the transport player, so only the
                    // gate had to follow it here.
                    val transportPlayer = host.activeTransportPlayer() ?: break
                    // Not this book's media yet (a switch still landing): skip the tick, keep the loop.
                    val bookId = currentBookId ?: continue

                    if (transportPlayer.isPlaying) {
                        val positionMs = bookRelativePositionMs()
                        val speed = transportPlayer.playbackParameters.speed
                        progressTracker.onPositionUpdate(bookId = bookId, positionMs = positionMs, speed = speed)
                        // Audio is playing and the book is known: the recorder opens the span here if the play
                        // that should have opened it went by before the book was known.
                        listeningEventRecorder.onPeriodicTick(
                            bookId = bookId.value,
                            positionMs = positionMs,
                            playbackSpeed = speed,
                        )
                    }
                }
            }

        // Second, faster poll driving in-app UI position display. This used to be
        // MediaControllerHolder's job — it polled the MediaController it held on a 250ms loop —
        // but the session player is now ChapterWindowPlayer, the chapter-scoped presentation
        // wrapper (see activeTransportPlayer's KDoc), so a MediaController's coordinates are
        // chapter-relative and can no longer feed PlaybackStateWriter.updatePositionFromMediaItem
        // (it interprets its arguments as file coordinates). The service reads the raw transport
        // player instead, which was never re-presented. Running it here rather than in-app also
        // means the notification's chapter rollover (driven by playbackManager.onChapterChanged)
        // now advances even when no UI client is bound, since the service itself keeps polling.
        uiPositionJob?.cancel()
        uiPositionJob =
            serviceScope.launch {
                while (isActive) {
                    delay(POSITION_UI_UPDATE_INTERVAL)
                    val p = host.activeTransportPlayer() ?: break
                    if (p.isPlaying) {
                        playbackStateWriter.updatePositionFromMediaItem(p.currentMediaItemIndex, p.currentPosition)
                    }
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        uiPositionJob?.cancel()
        uiPositionJob = null
    }

    /**
     * Progress bookkeeping for an is-playing event from either transport.
     *
     * Shared by both listeners so a cast session is recorded exactly like a local one. Which
     * events count is [playbackTransitionFor]'s decision — see its KDoc for why events from the
     * inactive player must be dropped in both directions.
     */
    fun handleIsPlayingChanged(
        source: TransportSource,
        isPlaying: Boolean,
    ) {
        // Records, through the refusal tracker, two facts in one: whether audio is sounding, and — since
        // the book talking again is the end of any focus interruption — that the next refused start is
        // genuinely a cold one.
        val transition = transport.onIsPlayingChanged(source, isPlaying)
        logger.info { "Is playing: $isPlaying (source=$source, casting=${transport.casting}) → $transition" }
        if (transition == PlaybackTransition.IGNORE) return

        val bookId = currentBookId
        // Speed and position must come from whichever player is actually the transport — reading
        // the local player during a cast session reports the speed of a player that has been
        // sitting paused since hand-off.
        val player = host.activeTransportPlayer()

        if (transition == PlaybackTransition.START) {
            // Audio is sounding, so any refusal notice is stale — clear it rather than leave
            // the listener with a notification telling them to fix something already fixed.
            refusalNotifier.clearRefusal()
            cancelIdleTimer()
            startPositionUpdates()

            if (bookId == null) {
                // A resume from outside the app after the process died can play before the book is known.
                // The first position tick that knows it opens the listening span instead.
                logger.info { "Playing before the book is known — the listening span opens on the first tick" }
            }
            if (bookId != null && player != null) {
                val positionMs = bookRelativePositionMs()
                val speed = player.playbackParameters.speed
                progressTracker.onPlaybackStarted(
                    bookId = bookId,
                    positionMs = positionMs,
                    speed = speed,
                    durationMs = bookDurationMs(),
                )
                serviceScope.launch {
                    listeningEventRecorder.onPlay(
                        bookId = bookId.value,
                        positionMs = positionMs,
                        playbackSpeed = speed,
                    )
                }
            }
        } else {
            endListen()

            // Context-aware idle timer based on why playback stopped
            val isSleepTimerPause = sleepTimerManager.state.value is SleepTimerState.FadingOut
            if (isSleepTimerPause) {
                startIdleTimer(IDLE_TIMEOUT_SLEEP, "sleep_timer")
            } else {
                startIdleTimer(IDLE_TIMEOUT_SHORT, "paused")
            }
        }
    }

    /**
     * Closes the listen on the transport player: stops the position loops, saves where it stopped and
     * finalizes the span. A pause does this, and so does a hand-off between the local and cast players.
     */
    fun endListen() {
        stopPositionUpdates()
        val bookId = currentBookId ?: return
        val player = host.activeTransportPlayer() ?: return
        val positionMs = bookRelativePositionMs()
        progressTracker.onPlaybackPaused(
            bookId = bookId,
            positionMs = positionMs,
            speed = player.playbackParameters.speed,
            durationMs = bookDurationMs(),
        )
        serviceScope.launch {
            listeningEventRecorder.onPause(bookId = bookId.value, positionMs = positionMs)
        }
        saveRefinedMeasurement(bookId = bookId, positionMs = positionMs)
    }

    fun cancelJobs() {
        idleJob?.cancel()
        positionUpdateJob?.cancel()
        uiPositionJob?.cancel()
    }

    companion object {
        // Idle timeout tiers
        internal val IDLE_TIMEOUT_SHORT = 30.minutes // After natural pause
        internal val IDLE_TIMEOUT_LONG = 2.hours // After book completion
        internal val IDLE_TIMEOUT_SLEEP = 5.minutes // After sleep timer fires

        // Position update interval
        private const val POSITION_UPDATE_INTERVAL = 30_000L // 30 seconds

        // UI-rate position poll interval — matches MediaControllerHolder's former poll cadence
        // (see startPositionUpdates' KDoc for why this now lives here instead).
        private const val POSITION_UI_UPDATE_INTERVAL = 250L
    }
}
