package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.playback.EnginePhase
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackStart
import com.calypsan.listenup.client.playback.PlayerEngine
import com.calypsan.listenup.client.playback.chapterInfoAt
import com.calypsan.listenup.client.playback.landingMs
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PlayerEngine]: state is settable, every command is recorded in [calls]. */
class FakePlayerEngine : PlayerEngine {
    override val loadedBookId = MutableStateFlow<BookId?>(null)
    override val phase = MutableStateFlow(EnginePhase.IDLE)
    override val positionMs = MutableStateFlow(0L)
    override val durationMs = MutableStateFlow(0L)
    override val speed = MutableStateFlow(1.0f)
    override val boostDb = MutableStateFlow(0f)
    override val chapters = MutableStateFlow<List<Chapter>>(emptyList())
    override val currentChapter = MutableStateFlow<PlaybackManager.ChapterInfo?>(null)
    override val timeline = MutableStateFlow<PlaybackTimeline?>(null)
    override val outputDeviceName = MutableStateFlow<String?>(null)
    override val error = MutableStateFlow<AppError?>(null)

    val calls = mutableListOf<String>()

    /** Runs on every recorded call, on the calling thread (a JVM spec reads which thread that was). */
    var onCall: (String) -> Unit = {}
    val loads = mutableListOf<Pair<BookId, PlaybackStart>>()
    var loadResult: AppResult<Unit> = AppResult.Success(Unit)
    var loadSuspension: suspend () -> Unit = {}

    /** Chapters and duration a successful [load] installs (what a prepare would return). */
    var preparedChapters: List<Chapter> = emptyList()
    var preparedDurationMs: Long = 0L
    var preparedPositionMs: Long = 0L

    /** When set, [setVolume] throws it (a fade that fails part-way). */
    var volumeFailure: Exception? = null

    override fun beginLoad(bookId: BookId) {
        record("beginLoad:${bookId.value}")
    }

    override suspend fun load(
        bookId: BookId,
        start: PlaybackStart,
    ): AppResult<Unit> {
        loads += bookId to start
        loadSuspension()
        if (loadResult is AppResult.Success) {
            chapters.value = preparedChapters
            durationMs.value = preparedDurationMs
            // preparedPositionMs stands for where the preparer's reconcile lands; moves replay from it.
            setPosition(
                when (start) {
                    PlaybackStart.Resume -> {
                        preparedPositionMs
                    }

                    is PlaybackStart.At -> {
                        start.positionMs
                    }

                    is PlaybackStart.Moved -> {
                        landingMs(
                            preparedPositionMs,
                            start.moves,
                            preparedChapters,
                            preparedDurationMs,
                        )
                    }
                },
            )
            loadedBookId.value = bookId
            phase.value = EnginePhase.PLAYING
        }
        return loadResult
    }

    override fun abandonLoad() {
        record("abandonLoad")
    }

    override fun play() {
        record("play")
        if (loadedBookId.value != null) phase.value = EnginePhase.PLAYING
    }

    override fun pause() {
        record("pause")
        // A pause leaves an idle engine idle, as Media3 does after a fatal error: there is nothing to pause.
        if (loadedBookId.value != null && phase.value != EnginePhase.IDLE) phase.value = EnginePhase.PAUSED
    }

    override fun seekTo(positionMs: Long) {
        record("seekTo:$positionMs")
        setPosition(positionMs)
    }

    override fun setSpeed(speed: Float) {
        record("setSpeed:$speed")
        this.speed.value = speed
    }

    override fun resetSpeed(defaultSpeed: Float) {
        record("resetSpeed:$defaultSpeed")
        speed.value = defaultSpeed
    }

    override fun setBoost(boostDb: Float) {
        record("setBoost:$boostDb")
        this.boostDb.value = boostDb
    }

    override fun resetBoost(defaultBoostDb: Float) {
        record("resetBoost:$defaultBoostDb")
        boostDb.value = defaultBoostDb
    }

    override fun setVolume(volume: Float) {
        record("setVolume:$volume")
        volumeFailure?.let { throw it }
    }

    override fun clearError() {
        record("clearError")
        error.value = null
    }

    override fun stop() {
        record("stop")
        loadedBookId.value = null
        phase.value = EnginePhase.IDLE
    }

    private fun record(call: String) {
        calls += call
        onCall(call)
    }

    /** Move the playhead the way a real engine reports it, keeping [currentChapter] derived the one way. */
    fun setPosition(ms: Long) {
        positionMs.value = ms
        currentChapter.value = chapterInfoAt(ms, chapters.value, durationMs.value)
    }
}
