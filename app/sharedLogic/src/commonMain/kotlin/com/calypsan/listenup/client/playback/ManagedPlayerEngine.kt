package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * [PlayerEngine] over today's [PlaybackManager] (read side) and [PlaybackController] (commands): Android,
 * desktop and web. No behaviour change for those platforms; it is the same pair of calls the retired
 * Android player ViewModel and web `LivePlayback` made, in one place.
 */
internal class ManagedPlayerEngine(
    private val playbackManager: PlaybackManager,
    private val playbackController: PlaybackController,
    private val startGate: PlaybackStartGate,
    outputRoute: OutputRoute,
    scope: CoroutineScope,
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
    ): AppResult<Unit> =
        when (val prepared = playbackManager.prepareForPlayback(bookId, start)) {
            is AppResult.Failure -> {
                prepared
            }

            is AppResult.Success -> {
                playbackManager.activateBook(bookId)
                playbackController.startPlayback(prepared.data)
                AppResult.Success(Unit)
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
     * Stop and forget the book. The pause is recorded *before* the manager forgets the book: on web and
     * desktop the manager's own observation persists a pause, and [PlaybackManager.clearPlayback] cancels it
     * (the old web `closeBook` rule). On Android the flag that routes this through the reporter is off,
     * so it only moves the state flow.
     */
    override fun stop() {
        if (playbackManager.playing.value) playbackManager.setPlaybackState(PlaybackState.Paused)
        playbackController.stop()
        startGate.release()
        playbackManager.clearPlayback()
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
