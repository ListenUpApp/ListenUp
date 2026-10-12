package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * The one owner of this device's loaded book (spec §4; Architecture in the PR 1 plan).
 *
 * Owns the lifecycle (restore, load, supersede, close, the revoke/delete teardown), the phase, the reasons
 * a book can't play before Play is pressed, the sleep-timer wiring, and every playback command. A Koin
 * `single`, created at start, so system entry points that have no screen (Media3 resumption, Android Auto,
 * iOS App Intents, CarPlay) and every screen share one generation counter, one fade and one teardown.
 * `PlayerViewModel` projects it for the UI; non-UI holders call it directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSession(
    private val engine: PlayerEngine,
    private val loadedBooks: LoadedBookRepository,
    private val bookRepository: BookRepository,
    private val downloadRepository: DownloadRepository,
    private val positionRepository: PlaybackPositionRepository,
    private val sleepTimer: SleepTimerManager,
    playbackPreferences: PlaybackPreferences,
    private val scope: CoroutineScope,
) {
    private val stage = MutableStateFlow<SessionStage?>(null)
    private val restoring = MutableStateFlow(true)
    private var generation = 0
    private var loadJob: Job? = null

    /** The book a load is in flight for, set synchronously so a double tap is swallowed in the same tick. */
    val preparingBookId: StateFlow<BookId?>
        field = MutableStateFlow<BookId?>(null)

    /** Where a not-prepared book will start when Play is pressed (Decision 5), or null for the saved position. */
    val pendingStartMs: StateFlow<Long?>
        field = MutableStateFlow<Long?>(null)

    private val stageBookId: Flow<BookId?> = stage.map { it?.bookId }.distinctUntilChanged()

    private val engineBound: StateFlow<Boolean> =
        combine(stage, engine.loadedBookId) { s, held -> s is SessionStage.Engine && held == s.bookId }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val savedPosition: StateFlow<PlaybackPosition?> =
        stageBookId
            .flatMapLatest { id -> id?.let(positionRepository::observe) ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val roomChapters: StateFlow<List<Chapter>> =
        stageBookId
            .flatMapLatest { id -> id?.let { bookRepository.observeChapters(it.value) } ?: flowOf(emptyList()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val roomDurationMs: StateFlow<Long> =
        stageBookId
            .flatMapLatest { id ->
                id?.let { bookRepository.observeBookDetail(it.value).map { detail -> detail?.duration ?: 0L } }
                    ?: flowOf(0L)
            }.stateIn(scope, SharingStarted.Eagerly, 0L)

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

    /** Forward skip, in seconds. `Eagerly`: a command reads `.value` whether or not anything is collecting. */
    val skipForwardSec: StateFlow<Int> =
        playbackPreferences
            .observeDefaultSkipForwardSec()
            .stateIn(scope, SharingStarted.Eagerly, PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC)

    /** Back skip, in seconds. `Eagerly`, for the same reason as [skipForwardSec]. */
    val skipBackwardSec: StateFlow<Int> =
        playbackPreferences
            .observeDefaultSkipBackwardSec()
            .stateIn(scope, SharingStarted.Eagerly, PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC)

    /** The sleep timer, as the shared manager holds it. */
    val sleepTimerState: StateFlow<SleepTimerState> get() = sleepTimer.state

    private val phaseInputs: Flow<PhaseInputs?> =
        stageBookId.flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(
                    flow = engine.phase,
                    flow2 = engine.error,
                    flow3 = engine.loadedBookId,
                    flow4 = positionRepository.observe(id).map { it?.isFinished == true },
                ) { phase, error, engineBook, finished ->
                    PhaseInputs(
                        forBookId = id,
                        enginePhase = phase,
                        engineError = error,
                        engineBookId = engineBook,
                        isFading = false,
                        isFinishedRow = finished,
                        unavailable = null,
                    )
                }
            }
        }

    /** The session's one state. Never re-emits on a position tick. */
    val state: StateFlow<PlayerSessionState> =
        combine(restoring, stage, phaseInputs) { isRestoring, s, inputs ->
            when {
                isRestoring -> {
                    PlayerSessionState.Restoring
                }

                s == null -> {
                    PlayerSessionState.NothingLoaded
                }

                else -> {
                    val current = inputs?.takeIf { it.forBookId == s.bookId } ?: neutralInputs(s.bookId)
                    PlayerSessionState.Loaded(
                        bookId = s.bookId,
                        phase = playerPhaseOf(s, current),
                        isEngineBound = s is SessionStage.Engine && current.engineBookId == s.bookId,
                    )
                }
            }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PlayerSessionState.Restoring)

    init {
        scope.launch { loadedBooks.observeLoadedBookId().collect(::onPersistedBookId) }
        scope.launch { engine.loadedBookId.collect(::onEngineBook) }
        scope.launch { watchLiveness() }
    }

    // ── Lifecycle (Task 13) ──────────────────────────────────────────────────────────────────────────

    private fun onPersistedBookId(persisted: BookId?) {
        val current = stage.value
        when {
            persisted == null -> {
                if (current != null) unloadInMemory()
            }

            current == null || (current.bookId != persisted && current !is SessionStage.Preparing) -> {
                stage.value = SessionStage.Idle(persisted)
                pendingStartMs.value = null
            }
        }
        restoring.value = false
    }

    private fun onEngineBook(held: BookId?) {
        val current = stage.value
        when {
            current is SessionStage.Preparing -> {
                // The load in flight decides what is loaded; the engine's interim book must not interrupt it.
            }

            held == null -> {
                if (current is SessionStage.Engine) stage.value = SessionStage.Idle(current.bookId)
            }

            current == null || current.bookId != held -> {
                stage.value = SessionStage.Engine(held)
                pendingStartMs.value = null
                scope.launch { loadedBooks.setLoaded(held) }
            }

            current is SessionStage.Idle -> {
                stage.value = SessionStage.Engine(held)
            }
        }
    }

    private suspend fun watchLiveness() {
        stageBookId
            .flatMapLatest { id ->
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
            }.distinctUntilChanged()
            .collect { verdict ->
                when (verdict) {
                    Liveness.Live -> Unit
                    Liveness.RowGone -> closeBook()
                    Liveness.Revoked -> tearDownRevoked()
                }
            }
    }

    /** Stop a revoked or deleted book but keep it loaded, so the bar can say why (§3.4). */
    private fun tearDownRevoked() {
        val current = stage.value ?: return
        logger.info { "Loaded book ${current.bookId.value} left the library; stopping it" }
        cancelLoad()
        if (engine.loadedBookId.value == current.bookId) engine.stop()
        stage.value = SessionStage.Gone(current.bookId)
    }

    /** Close book (F1): stop, cancel the timer, forget the book on this device. */
    fun closeBook() {
        cancelLoad()
        engine.stop()
        sleepTimer.cancelTimer()
        unloadInMemory()
        scope.launch { loadedBooks.clear() }
    }

    private fun unloadInMemory() {
        cancelLoad()
        stage.value = null
        pendingStartMs.value = null
    }

    private fun cancelLoad() {
        val wasLoading = loadJob?.isActive == true
        loadJob?.cancel()
        loadJob = null
        generation++
        preparingBookId.value = null
        if (wasLoading) engine.abandonLoad()
    }

    private fun neutralInputs(bookId: BookId) =
        PhaseInputs(
            forBookId = bookId,
            enginePhase = engine.phase.value,
            engineError = engine.error.value,
            engineBookId = engine.loadedBookId.value,
            isFading = false,
            isFinishedRow = false,
            unavailable = null,
        )

    // ── Load (Task 14) ───────────────────────────────────────────────────────────────────────────────
    // ── Transport (Task 15) ──────────────────────────────────────────────────────────────────────────
    // ── Errors (Task 16) ─────────────────────────────────────────────────────────────────────────────
    // ── Sleep (Task 17) ──────────────────────────────────────────────────────────────────────────────
}
