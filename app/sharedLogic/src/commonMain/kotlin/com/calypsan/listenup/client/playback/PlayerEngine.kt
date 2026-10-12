package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.StateFlow

/** What the audio engine says it is doing. Set only by the engine; the session derives the player's phase from it. */
enum class EnginePhase {
    /** Nothing loaded, or loaded and not yet reporting. */
    IDLE,

    /** Loaded and stalled, waiting for audio. */
    BUFFERING,

    /** Audio is advancing. */
    PLAYING,

    /** Loaded and paused. */
    PAUSED,

    /** Played to the end of the last file. */
    ENDED,
}

/**
 * The audio engine the shared [PlayerSession] drives (spec §4.1, S1).
 *
 * Two implementations: [ManagedPlayerEngine] over `PlaybackManager` + `PlaybackController` (Android,
 * desktop, web), and [NativePlayerEngine], which forwards commands to Swift's `PlayerCoordinator` (iOS).
 * Members have no default implementations on purpose: Mokkery ignores interface defaults.
 */
interface PlayerEngine {
    /** The book the engine holds audio for, or null when nothing is loaded into it. */
    val loadedBookId: StateFlow<BookId?>

    /** What the engine reports it is doing. */
    val phase: StateFlow<EnginePhase>

    /** Book-relative playhead, in milliseconds. Ticks while playing. */
    val positionMs: StateFlow<Long>

    /** The loaded book's duration, in milliseconds; 0 with nothing loaded. */
    val durationMs: StateFlow<Long>

    /** The rate the engine plays at. */
    val speed: StateFlow<Float>

    /** The loaded book's user volume boost, in dB. */
    val boostDb: StateFlow<Float>

    /** The loaded book's chapters, as prepared. */
    val chapters: StateFlow<List<Chapter>>

    /** [chapterInfoAt] over [positionMs] and [chapters]: the one chapter derivation. */
    val currentChapter: StateFlow<PlaybackManager.ChapterInfo?>

    /** The prepared timeline (which files are local, which stream, which are transcoded), or null. */
    val timeline: StateFlow<PlaybackTimeline?>

    /** The remote output audio goes to (a Cast device, an AirPlay route), or null when it plays here. */
    val outputDeviceName: StateFlow<String?>

    /** The engine's current failure, typed at its edge, or null. */
    val error: StateFlow<AppError?>

    /** Called synchronously inside the user's gesture, before [load], so a browser counts the tap. */
    fun beginLoad(bookId: BookId)

    /**
     * Prepare [bookId] through the playback choke point and hand it to the engine, which starts playing.
     * [startAtMs] starts there (book ms); null resumes from the saved position.
     */
    suspend fun load(
        bookId: BookId,
        startAtMs: Long?,
    ): AppResult<Unit>

    /** A [beginLoad] whose [load] did not complete (failed, superseded, cancelled): undo what it armed. */
    fun abandonLoad()

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Play at [speed] and record it as this book's own rate. */
    fun setSpeed(speed: Float)

    /** Play at [defaultSpeed] and record that this book follows the default. */
    fun resetSpeed(defaultSpeed: Float)

    /** Boost this book by [boostDb] and record it. */
    fun setBoost(boostDb: Float)

    /** Boost by [defaultBoostDb] and record that this book follows the default. */
    fun resetBoost(defaultBoostDb: Float)

    /** Output volume, 0..1. Used by the sleep fade. */
    fun setVolume(volume: Float)

    /** Forget [error] (a user dismissed it, or is retrying). */
    fun clearError()

    /** Stop and unload. [loadedBookId] becomes null. */
    fun stop()
}
