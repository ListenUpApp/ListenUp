package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * What [PlayerSession] reads out for the loaded book: the engine's values while it holds the book, else Room's,
 * the saved position's and the pending start's. Every flow is `Eagerly` shared, so a command can read `.value`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerReadout(
    engine: PlayerEngine,
    stage: StateFlow<SessionStage?>,
    pendingStart: StateFlow<PlaybackStart>,
    bookRepository: BookRepository,
    downloadRepository: DownloadRepository,
    positionRepository: PlaybackPositionRepository,
    playbackPreferences: PlaybackPreferences,
    scope: CoroutineScope,
) {
    val stageBookId: Flow<BookId?> = stage.map { it?.bookId }.distinctUntilChanged()

    val engineBound: StateFlow<Boolean> =
        combine(stage, engine.loadedBookId) { s, held -> s is SessionStage.Engine && held == s.bookId }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val savedPosition: StateFlow<PlaybackPosition?> =
        stageBookId
            .flatMapLatest { id -> id?.let(positionRepository::observe) ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val roomChapters: StateFlow<List<Chapter>> =
        stageBookId
            .flatMapLatest { id -> id?.let { bookRepository.observeChapters(it.value) } ?: flowOf(emptyList()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val roomDurationMs: StateFlow<Long> =
        stageBookId
            .flatMapLatest { id ->
                id?.let { bookRepository.observeBookDetail(it.value).map { detail -> detail?.duration ?: 0L } }
                    ?: flowOf(0L)
            }.stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * Where a not-prepared book shows it will start (Decision 5), or null for the saved position. Moves are shown
     * from this device's saved position; Play replays them from the reconciled one, which may be another device's.
     */
    val pendingStartMs: StateFlow<Long?> =
        combine(
            flow = pendingStart,
            flow2 = savedPosition,
            flow3 = roomChapters,
            flow4 = roomDurationMs,
        ) { start, saved, chapters, duration ->
            when (start) {
                PlaybackStart.Resume -> {
                    null
                }

                is PlaybackStart.At -> {
                    start.positionMs
                }

                is PlaybackStart.Moved -> {
                    landingMs(
                        fromMs = saved?.positionMs ?: 0L,
                        moves = start.moves,
                        chapters = chapters,
                        durationMs = duration,
                    )
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Book-relative playhead: the engine's while it holds the book, else the pending start or the saved position. Ticks. */
    val positionMs: StateFlow<Long> =
        combine(
            flow = engineBound,
            flow2 = engine.positionMs,
            flow3 = pendingStartMs,
            flow4 = savedPosition,
        ) { bound, enginePosition, pending, saved ->
            if (bound) enginePosition else pending ?: saved?.positionMs ?: 0L
        }.stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * The engine's playhead, attributed to a book only from a position it reports after it took that book: an engine
     * names its next book before it moves its position (PlaybackManager sets the id, then the start), so a position
     * from before the switch is never paired with the new id. Null until such a position arrives.
     */
    private val enginePlayhead: StateFlow<BookPlayhead?> =
        merge(
            engine.loadedBookId.map { EngineReport.Book(it) },
            engine.positionMs.map { EngineReport.Position(it) },
        ).scan(EngineHeld(bookId = null, positionMs = null)) { held, report ->
            when (report) {
                is EngineReport.Book -> EngineHeld(bookId = report.bookId, positionMs = null)
                is EngineReport.Position -> held.copy(positionMs = report.positionMs)
            }
        }.map { held -> held.bookId?.let { id -> held.positionMs?.let { BookPlayhead(id, it) } } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** [enginePlayhead] while the engine holds the session's book; null otherwise (the saved position stands). */
    val playhead: StateFlow<BookPlayhead?> =
        combine(stage, enginePlayhead) { s, live ->
            live?.takeIf { s is SessionStage.Engine && it.bookId == s.bookId }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The loaded book's chapters: the engine's prepared list, or Room's before a prepare. */
    val chapters: StateFlow<List<Chapter>> =
        combine(engineBound, engine.chapters, roomChapters) { bound, prepared, room -> if (bound) prepared else room }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The loaded book's duration, in ms. */
    val durationMs: StateFlow<Long> =
        combine(
            engineBound,
            engine.durationMs,
            roomDurationMs,
        ) { bound, prepared, room -> if (bound) prepared else room }
            .stateIn(scope, SharingStarted.Eagerly, 0L)

    /** The current chapter, from the one derivation ([chapterInfoAt]) whichever side holds the book. */
    val currentChapter: StateFlow<PlaybackManager.ChapterInfo?> =
        combine(
            flow = engineBound,
            flow2 = engine.currentChapter,
            flow3 = positionMs,
            flow4 = roomChapters,
            flow5 = roomDurationMs,
        ) { bound, engineChapter, position, room, duration ->
            if (bound) engineChapter else chapterInfoAt(position, room, duration)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The rate: the engine's, else this book's saved rate, else the default. */
    val speed: StateFlow<Float> =
        combine(
            flow = engineBound,
            flow2 = engine.speed,
            flow3 = savedPosition,
            flow4 = playbackPreferences.observeDefaultPlaybackSpeed(),
        ) { bound, engineSpeed, saved, default ->
            if (bound) engineSpeed else saved?.playbackSpeed ?: default
        }.stateIn(scope, SharingStarted.Eagerly, PlaybackPreferences.DEFAULT_PLAYBACK_SPEED)

    /** The boost in dB: the engine's, else this book's saved boost, else the default. */
    val boostDb: StateFlow<Float> =
        combine(
            flow = engineBound,
            flow2 = engine.boostDb,
            flow3 = savedPosition,
            flow4 = playbackPreferences.observeDefaultVolumeBoostDb(),
        ) { bound, engineBoost, saved, default ->
            if (bound) engineBoost else saved?.volumeBoostDb ?: default
        }.stateIn(scope, SharingStarted.Eagerly, PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB)

    /**
     * The loaded book's standing in the local mirror (§3.4), keyed on the book too: one revoked book straight after
     * another must still tear the second down.
     */
    val liveness: Flow<Pair<BookId?, Liveness>> =
        stageBookId
            .flatMapLatest { id ->
                val verdicts =
                    if (id == null) {
                        flowOf(Liveness.Live)
                    } else {
                        combine(
                            flow = bookRepository.observeBookDetail(id.value).map { it != null },
                            flow2 = bookRepository.observeIsBookLive(id.value),
                            flow3 = downloadRepository.observeBookStatus(id),
                            flow4 = positionRepository.observe(id),
                        ) { exists, live, download, position ->
                            livenessOf(
                                rowExists = exists,
                                isLive = live,
                                isDownloaded = download is BookDownloadStatus.Completed,
                                positionMs = position?.positionMs ?: 0L,
                                isFinished = position?.isFinished == true,
                            )
                        }
                    }
                verdicts.map { verdict -> id to verdict }
            }.distinctUntilChanged()
}

/** One report from the engine, in the order it made them. */
private sealed interface EngineReport {
    data class Book(
        val bookId: BookId?,
    ) : EngineReport

    data class Position(
        val positionMs: Long,
    ) : EngineReport
}

/** The book the engine holds and the first position it reported for it, if it has yet. */
private data class EngineHeld(
    val bookId: BookId?,
    val positionMs: Long?,
)
