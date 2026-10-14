package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [PlayerEngine] over today's [PlaybackManager] (read side) and [PlaybackController] (commands): Android,
 * desktop and web. No behaviour change for those platforms; it is the same pair of calls the retired
 * Android player ViewModel and web `LivePlayback` made, in one place.
 */
internal class ManagedPlayerEngine(
    private val playbackManager: PlaybackManager,
    private val playbackController: PlaybackController,
    private val startGate: PlaybackStartGate,
    private val progressTracker: ProgressTracker,
    outputRoute: OutputRoute,
    private val scope: CoroutineScope,
) : PlayerEngine {
    override val loadedBookId: StateFlow<BookId?> get() = playbackManager.currentBookId
    override val positionMs: StateFlow<Long> get() = playbackManager.currentPositionMs
    override val durationMs: StateFlow<Long> get() = playbackManager.totalDurationMs
    override val speed: StateFlow<Float> get() = playbackManager.playbackSpeed
    override val boostDb: StateFlow<Float> get() = playbackManager.volumeBoostDb
    override val chapters: StateFlow<List<Chapter>> get() = playbackManager.chapters
    override val currentChapter: StateFlow<PlaybackManager.ChapterInfo?> get() = playbackManager.currentChapter
    override val timeline: StateFlow<PlaybackTimeline?> get() = playbackManager.currentTimeline
    override val error: StateFlow<AppError?> get() = playbackManager.playbackError
    override val outputDeviceName: StateFlow<String?> = outputRoute.deviceName

    /** The book a [load] is bringing up, until it lands or is abandoned. Touched only on the player scope. */
    private var loadingBookId: BookId? = null

    override val phase: StateFlow<EnginePhase> =
        combine(
            flow = playbackManager.playbackState,
            flow2 = playbackManager.playing,
            flow3 = playbackManager.buffering,
            transform = ::enginePhaseOf,
        ).stateIn(scope, SharingStarted.Eagerly, EnginePhase.IDLE)

    /**
     * Silence the outgoing book at once when another one is asked for, so a load that fails does not leave it
     * playing behind the new book's error (what [NativePlayerEngine] does too). The pause comes before the
     * prime: on the web a pause after it would cancel the tap's standing "play what loads next".
     */
    override fun beginLoad(bookId: BookId) {
        val current = playbackManager.currentBookId.value
        if (current != null && current != bookId && playbackManager.playing.value) playbackController.pause()
        startGate.prime()
    }

    override suspend fun load(
        bookId: BookId,
        start: PlaybackStart,
    ): AppResult<Unit> {
        loadingBookId = bookId
        try {
            return when (val prepared = playbackManager.prepareForPlayback(bookId, start)) {
                is AppResult.Failure -> {
                    prepared
                }

                is AppResult.Success -> {
                    playbackManager.activateBook(bookId)
                    startOrLetGo(bookId, prepared.data)
                    AppResult.Success(Unit)
                }
            }
        } finally {
            if (loadingBookId == bookId) loadingBookId = null
        }
    }

    /**
     * Start [bookId], already activated, or let go of it if the start throws (the browser cannot fetch hls.js from a
     * server that just died). An engine that kept naming a book it never started would read as holding it, and the
     * session would take that for the engine playing it by itself and drop the error for a silent Paused, as the
     * iOS engine never adopts a book whose start failed.
     */
    private suspend fun startOrLetGo(
        bookId: BookId,
        prepared: PlaybackManager.PrepareResult,
    ) {
        try {
            playbackController.startPlayback(prepared)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (playbackManager.currentBookId.value == bookId) playbackManager.clearPlayback()
            throw e
        }
    }

    override fun abandonLoad() = startGate.withdraw()

    override fun play() = playbackController.play()

    override fun pause() = playbackController.pause()

    override fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
        // A seek while paused produces no tick; tell the manager so the playhead moves at once.
        playbackManager.updatePosition(positionMs)
    }

    override fun setSpeed(speed: Float) {
        playbackController.setPlaybackSpeed(speed)
        playbackManager.onSpeedChanged(speed)
    }

    override fun resetSpeed(defaultSpeed: Float) {
        playbackController.setPlaybackSpeed(defaultSpeed)
        playbackManager.onSpeedReset(defaultSpeed)
    }

    // Boost reaches the platform gain stage through PlaybackManager.effectiveGainDb; no controller call.
    override fun setBoost(boostDb: Float) = playbackManager.onVolumeBoostChanged(boostDb)

    override fun resetBoost(defaultBoostDb: Float) = playbackManager.onBoostReset(defaultBoostDb)

    override fun setVolume(volume: Float) = playbackController.setVolume(volume)

    override fun clearError() = playbackManager.clearError()

    /**
     * Stop and forget the book, recording its place first (Close, sign-out's Close, the revoke teardown), as
     * iOS's coordinator does. The pause is recorded *before* the manager forgets the book: on web and desktop
     * the manager's own observation persists a pause, and [PlaybackManager.clearPlayback] cancels it (the old
     * web `closeBook` rule). On Android that flag is off and the service's own listener hears the stop only
     * after the manager has forgotten the book, so without the exact save below a Close lost everything since
     * the last 30 s periodic save.
     */
    override fun stop() {
        val place = placeBeingDropped()
        if (playbackManager.playing.value) playbackManager.setPlaybackState(PlaybackState.Paused)
        playbackController.stop()
        startGate.release()
        playbackManager.clearPlayback()
        place?.let { (bookId, positionMs) -> scope.launch { progressTracker.savePositionNow(bookId, positionMs) } }
    }

    /**
     * The held book and its exact place, or null when nothing is held or the held book is the one still
     * loading: between [PlaybackManager.activateBook] and the start, the manager names the new book but still
     * carries the old book's position, which must never be filed under the new one.
     */
    private fun placeBeingDropped(): Pair<BookId, Long>? {
        val bookId = playbackManager.currentBookId.value ?: return null
        if (bookId == loadingBookId) return null
        return bookId to playbackManager.currentPositionMs.value
    }
}

/**
 * The manager's three flags as one engine phase. Pure.
 *
 * [playing] is the one truth about whether audio is moving, as it was for the retired player ViewModel.
 * On Android a pause leaves Media3 in STATE_READY, so [state] keeps reading Playing and only [playing]
 * drops: a Playing state with the flag down is a pause.
 */
internal fun enginePhaseOf(
    state: PlaybackState,
    playing: Boolean,
    buffering: Boolean,
): EnginePhase =
    when {
        playing -> {
            EnginePhase.PLAYING
        }

        buffering || state == PlaybackState.Buffering -> {
            EnginePhase.BUFFERING
        }

        state == PlaybackState.Ended -> {
            EnginePhase.ENDED
        }

        state == PlaybackState.Playing || state == PlaybackState.Paused || state is PlaybackState.Error -> {
            EnginePhase.PAUSED
        }

        else -> {
            EnginePhase.IDLE
        }
    }
