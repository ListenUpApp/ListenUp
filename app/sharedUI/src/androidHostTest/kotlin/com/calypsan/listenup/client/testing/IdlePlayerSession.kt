package com.calypsan.listenup.client.testing

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.EnginePhase
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackStart
import com.calypsan.listenup.client.playback.PlayerEngine
import com.calypsan.listenup.client.playback.PlayerSession
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.core.BookId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A real [PlayerSession] with nothing loaded, for UI tests whose screen injects the session (Book Detail's busy
 * button) but never plays. [PlayerSession] is a final class, so the screen gets the real thing over an engine
 * that refuses to prepare and repositories that are never asked anything while nothing is loaded.
 */
internal fun idlePlayerSession(scope: CoroutineScope): PlayerSession =
    PlayerSession(
        engine = IdlePlayerEngine,
        loadedBooks =
            mock<LoadedBookRepository>(MockMode.autoUnit) {
                every { observeLoadedBookId() } returns
                    flowOf(null)
            },
        bookRepository = mock(MockMode.autoUnit),
        downloadRepository = mock(MockMode.autoUnit),
        positionRepository = mock(MockMode.autoUnit),
        availability = mock(MockMode.autoUnit),
        inboxRepository = mock(MockMode.autoUnit),
        networkMonitor = mock(MockMode.autoUnit),
        serverReachability = mock(MockMode.autoUnit),
        authSession = mock(MockMode.autoUnit),
        sleepTimer = SleepTimerManager(scope = scope),
        playbackPreferences =
            mock<PlaybackPreferences>(MockMode.autoUnit) {
                every { observeDefaultPlaybackSpeed() } returns flowOf(PlaybackPreferences.DEFAULT_PLAYBACK_SPEED)
                every { observeDefaultVolumeBoostDb() } returns flowOf(PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB)
                every { observeDefaultSkipForwardSec() } returns flowOf(PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC)
                every { observeDefaultSkipBackwardSec() } returns flowOf(PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC)
            },
        offlineCopiesSupported = true,
        scope = scope,
    )

/**
 * An engine holding nothing that refuses every load: these screens never play. (`NativePlayerEngine`'s
 * constructor is internal to `:app:sharedLogic`, so this module brings its own.)
 */
private object IdlePlayerEngine : PlayerEngine {
    override val loadedBookId: StateFlow<BookId?> = MutableStateFlow(null)
    override val phase: StateFlow<EnginePhase> = MutableStateFlow(EnginePhase.IDLE)
    override val positionMs: StateFlow<Long> = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = MutableStateFlow(0L)
    override val speed: StateFlow<Float> = MutableStateFlow(1.0f)
    override val boostDb: StateFlow<Float> = MutableStateFlow(0f)
    override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(emptyList())
    override val currentChapter: StateFlow<PlaybackManager.ChapterInfo?> = MutableStateFlow(null)
    override val timeline: StateFlow<PlaybackTimeline?> = MutableStateFlow(null)
    override val outputDeviceName: StateFlow<String?> = MutableStateFlow(null)
    override val error: StateFlow<AppError?> = MutableStateFlow(null)

    override fun beginLoad(bookId: BookId) = Unit

    override suspend fun load(
        bookId: BookId,
        start: PlaybackStart,
    ): AppResult<Unit> = AppResult.Failure(PlaybackError.CouldNotStart())

    override fun abandonLoad() = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun resetSpeed(defaultSpeed: Float) = Unit

    override fun setBoost(boostDb: Float) = Unit

    override fun resetBoost(defaultBoostDb: Float) = Unit

    override fun setVolume(volume: Float) = Unit

    override fun clearError() = Unit

    override fun stop() = Unit
}
