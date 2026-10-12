package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.playback.EnginePhase
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlayerEngine
import com.calypsan.listenup.client.playback.chapterInfoAt
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
    val loads = mutableListOf<Pair<BookId, Long?>>()
    var loadResult: AppResult<Unit> = AppResult.Success(Unit)
    var loadSuspension: suspend () -> Unit = {}

    /** Chapters and duration a successful [load] installs (what a prepare would return). */
    var preparedChapters: List<Chapter> = emptyList()
    var preparedDurationMs: Long = 0L
    var preparedPositionMs: Long = 0L

    override fun beginLoad(bookId: BookId) {
        calls += "beginLoad:${bookId.value}"
    }

    override suspend fun load(
        bookId: BookId,
        startAtMs: Long?,
    ): AppResult<Unit> {
        loads += bookId to startAtMs
        loadSuspension()
        if (loadResult is AppResult.Success) {
            chapters.value = preparedChapters
            durationMs.value = preparedDurationMs
            setPosition(startAtMs ?: preparedPositionMs)
            loadedBookId.value = bookId
            phase.value = EnginePhase.PLAYING
        }
        return loadResult
    }

    override fun abandonLoad() {
        calls += "abandonLoad"
    }

    override fun play() {
        calls += "play"
        if (loadedBookId.value != null) phase.value = EnginePhase.PLAYING
    }

    override fun pause() {
        calls += "pause"
        if (loadedBookId.value != null) phase.value = EnginePhase.PAUSED
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo:$positionMs"
        setPosition(positionMs)
    }

    override fun setSpeed(speed: Float) {
        calls += "setSpeed:$speed"
        this.speed.value = speed
    }

    override fun resetSpeed(defaultSpeed: Float) {
        calls += "resetSpeed:$defaultSpeed"
        speed.value = defaultSpeed
    }

    override fun setBoost(boostDb: Float) {
        calls += "setBoost:$boostDb"
        this.boostDb.value = boostDb
    }

    override fun resetBoost(defaultBoostDb: Float) {
        calls += "resetBoost:$defaultBoostDb"
        boostDb.value = defaultBoostDb
    }

    override fun setVolume(volume: Float) {
        calls += "setVolume:$volume"
    }

    override fun clearError() {
        calls += "clearError"
        error.value = null
    }

    override fun stop() {
        calls += "stop"
        loadedBookId.value = null
        phase.value = EnginePhase.IDLE
    }

    /** Move the playhead the way a real engine reports it, keeping [currentChapter] derived the one way. */
    fun setPosition(ms: Long) {
        positionMs.value = ms
        currentChapter.value = chapterInfoAt(ms, chapters.value, durationMs.value)
    }
}
