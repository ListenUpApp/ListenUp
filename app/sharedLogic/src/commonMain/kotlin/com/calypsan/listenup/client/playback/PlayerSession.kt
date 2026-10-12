package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_MAX
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_MIN
import com.calypsan.listenup.client.presentation.player.skipTargetMs
import com.calypsan.listenup.client.presentation.player.stepPlaybackSpeed
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** The longest custom sleep timer (spec §4.3, PT8). */
internal const val MAX_SLEEP_MINUTES = 720

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
@Suppress("LongParameterList") // DI constructor: each param is a distinct domain responsibility.
class PlayerSession(
    private val engine: PlayerEngine,
    private val loadedBooks: LoadedBookRepository,
    private val bookRepository: BookRepository,
    private val downloadRepository: DownloadRepository,
    private val positionRepository: PlaybackPositionRepository,
    private val availability: BookAvailability,
    private val inboxRepository: InboxRepository,
    private val networkMonitor: NetworkMonitor,
    private val serverReachability: ServerReachability,
    private val authSession: AuthSession,
    private val sleepTimer: SleepTimerManager,
    private val playbackPreferences: PlaybackPreferences,
    /** True where the device keeps downloads; the browser does not, so it never says "offline, not downloaded". */
    val offlineCopiesSupported: Boolean,
    private val scope: CoroutineScope,
) {
    private val stage = MutableStateFlow<SessionStage?>(null)
    private val restoring = MutableStateFlow(true)
    private var generation = 0
    private var loadJob: Job? = null
    private var fadeJob: Job? = null

    /** The book a load is in flight for, set synchronously so a double tap is swallowed in the same tick. */
    val preparingBookId: StateFlow<BookId?>
        field = MutableStateFlow<BookId?>(null)

    /** [preparingBookId] behind a 250 ms debounce, for buttons that must not flash on a fast prepare (Decision 11). */
    val preparingBookIdUi: Flow<BookId?> = preparingBookIdUiFlow(preparingBookId)

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
                val engineFacts =
                    combine(engine.phase, engine.error, engine.loadedBookId) { p, e, b -> Triple(p, e, b) }
                combine(
                    flow = engineFacts,
                    flow2 = positionRepository.observe(id).map { it?.isFinished == true },
                    flow3 = observeUnavailableReason(id),
                    flow4 = sleepTimer.state.map { it is SleepTimerState.FadingOut },
                ) { (phase, error, engineBook), finished, unavailable, fading ->
                    PhaseInputs(
                        forBookId = id,
                        enginePhase = phase,
                        engineError = error,
                        engineBookId = engineBook,
                        isFading = fading,
                        isFinishedRow = finished,
                        unavailable = unavailable,
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
        scope.launch { stageBookId.collect { sleepTimer.onBookChanged(it?.value) } }
        scope.launch {
            engine.currentChapter
                .filterNotNull()
                .distinctUntilChangedBy { it.index }
                .collect { sleepTimer.onChapterChanged(it.index) }
        }
        scope.launch { sleepTimer.sleepEvent.collect { runFade() } }
    }

    // ── Lifecycle (Task 13) ──────────────────────────────────────────────────────────────────────────

    private fun onPersistedBookId(persisted: BookId?) {
        val current = stage.value
        when {
            persisted == null && current != null && isHeldInMemory(current) -> {
                // A cleared key that lands under a load in flight or a book the engine plays is stale: the cold
                // start's first read, or Close's clear arriving after the next Play. Write the book back.
                scope.launch { loadedBooks.setLoaded(current.bookId) }
            }

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

    /** A load in flight, or the engine holding the stage's book: what only Close (or sign-out's Close) unloads. */
    private fun isHeldInMemory(current: SessionStage): Boolean =
        current is SessionStage.Preparing ||
            (current is SessionStage.Engine && engine.loadedBookId.value == current.bookId)

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
            }
            // Keyed on the book too: one revoked book straight after another must still tear the second down.
            .distinctUntilChanged()
            .collect { (_, verdict) ->
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
        fadeJob?.cancel()
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

    private fun observeUnavailableReason(bookId: BookId): Flow<AppError?> =
        combine(
            flow = inboxRepository.observeHeldBookIds().map { bookId in it },
            flow2 = authSession.authState.map { it is AuthState.SessionLapsed },
            flow3 = availability.observe(bookId),
            flow4 = networkMonitor.onlineFlow,
        ) { held, lapsed, available, online ->
            unavailableReasonOf(
                isHeld = held,
                isSessionLapsed = lapsed,
                hasDownloadedAudio = available.downloadStatus is BookDownloadStatus.Completed,
                canPlay = available.canPlay,
                isDeviceOnline = online,
                offlineCopiesSupported = offlineCopiesSupported,
            )
        }.distinctUntilChanged()

    // ── Load (Task 14) ───────────────────────────────────────────────────────────────────────────────

    /**
     * Play [bookId] (the play entry for every surface and Home, P-R5). [startAtMs] starts there (book ms);
     * null resumes from the saved position.
     *
     * - A plain repeat tap for the book already preparing is swallowed (a double tap).
     * - The book the engine already holds, not finished and not failed, is a resume: a seek when [startAtMs]
     *   is given, then play. Never a re-prepare (the web rule, now everyone's).
     * - Anything else starts a load, which supersedes any load in flight.
     */
    fun playBook(
        bookId: BookId,
        startAtMs: Long?,
    ) {
        if (startAtMs == null && preparingBookId.value == bookId) return
        val loaded = loadedOrNull()
        val resumable =
            loaded != null &&
                loaded.bookId == bookId &&
                loaded.isEngineBound &&
                loaded.phase !is PlayerPhase.Finished &&
                loaded.phase !is PlayerPhase.Failed
        if (resumable) {
            if (startAtMs != null) engine.seekTo(startAtMs.coerceIn(0L, (durationMs.value - 1).coerceAtLeast(0L)))
            engine.clearError()
            engine.play()
            return
        }
        startLoad(bookId, startAtMs)
    }

    /**
     * Load [bookId] at [startAtMs]. Synchronous up to the engine's first suspension, so the bar names the new book
     * (§3.6) and a browser's gesture is spent ([PlayerEngine.beginLoad]) inside the tap. Only the newest
     * generation may finish: a superseded load's `finally` cannot touch its successor's state.
     */
    private fun startLoad(
        bookId: BookId,
        startAtMs: Long?,
    ) {
        cancelLoad()
        // cancelLoad() moved the generation on, so this load's generation is read after it.
        val myGeneration = generation
        preparingBookId.value = bookId
        stage.value = SessionStage.Preparing(bookId, myGeneration)
        pendingStartMs.value = null
        sleepTimer.onBookChanged(bookId.value)
        engine.beginLoad(bookId)
        loadJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var handedOver = false
                try {
                    loadedBooks.setLoaded(bookId)
                    val result = engine.load(bookId, startAtMs)
                    if (myGeneration != generation) return@launch
                    stage.value =
                        when (result) {
                            is AppResult.Success -> {
                                handedOver = true
                                SessionStage.Engine(bookId)
                            }

                            is AppResult.Failure -> {
                                SessionStage.Failed(bookId, result.error)
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Load of ${bookId.value} threw" }
                    if (myGeneration == generation) {
                        stage.value = SessionStage.Failed(bookId, PlaybackError.CouldNotStart(debugInfo = e.toString()))
                    }
                } finally {
                    if (myGeneration == generation) {
                        if (!handedOver) engine.abandonLoad()
                        preparingBookId.value = null
                        loadJob = null
                    }
                }
            }
    }

    private fun loadedOrNull(): PlayerSessionState.Loaded? = state.value as? PlayerSessionState.Loaded

    // ── Transport (Task 15) ──────────────────────────────────────────────────────────────────────────

    /** The one play/pause intent (§4.3): resume, pause, prepare a restored book, re-listen, or retry. */
    fun playPause() {
        val loaded = loadedOrNull() ?: return
        when (val phase = loaded.phase) {
            PlayerPhase.Playing, PlayerPhase.Buffering, PlayerPhase.SleepFading -> {
                engine.pause()
            }

            PlayerPhase.Paused -> {
                engine.clearError()
                engine.play()
            }

            PlayerPhase.Restored -> {
                startLoad(loaded.bookId, pendingStartMs.value)
            }

            PlayerPhase.Finished -> {
                startLoad(loaded.bookId, null)
            }

            is PlayerPhase.Failed -> {
                if (phase.error is PlaybackError.AutoplayBlocked || phase.error is PlaybackError.BlockedInBackground) {
                    engine.clearError()
                    engine.play()
                } else {
                    retry()
                }
            }

            is PlayerPhase.Unavailable -> {
                retry()
            }

            PlayerPhase.Preparing -> {
                // A load is in flight; its result decides.
            }
        }
    }

    /** Back by the user's interval × speed, clamped (§3.3). */
    fun skipBack() {
        skipBy(skipBackwardSec.value, forward = false)
    }

    /** Forward by the user's interval × speed, clamped. */
    fun skipForward() {
        skipBy(skipForwardSec.value, forward = true)
    }

    private fun skipBy(
        seconds: Int,
        forward: Boolean,
    ) {
        val phase = loadedOrNull()?.phase ?: return
        if (phase is PlayerPhase.Finished || phase is PlayerPhase.Preparing) return
        val duration = durationMs.value
        if (duration <= 0) return
        seekTo(
            skipTargetMs(
                currentPositionMs = positionMs.value,
                seconds = seconds,
                speed = speed.value,
                totalDurationMs = duration,
                forward = forward,
            ),
        )
    }

    /** §3.3: restart the chapter after 3 s, else the previous one; chapter 1 restarts. */
    fun previousChapter() {
        seekTo(previousChapterTargetMs(positionMs.value, chapters.value))
    }

    /** §3.3: the next chapter's start; a no-op at the last chapter. */
    fun nextChapter() {
        nextChapterTargetMs(positionMs.value, chapters.value)?.let(::seekTo)
    }

    /** The start of chapter [index]; an index the list no longer has is ignored, never clamped. */
    fun seekToChapter(index: Int) {
        chapters.value.getOrNull(index)?.let { seekTo(it.startTime) }
    }

    /** [fraction] of the current chapter (the whole book when it has no chapters). */
    fun seekWithinChapter(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val chapter = currentChapter.value
        val target =
            if (chapter == null) {
                (durationMs.value * clamped).toLong()
            } else {
                chapter.startMs + ((chapter.endMs - chapter.startMs) * clamped).toLong()
            }
        seekTo(target)
    }

    /**
     * Move the playhead. The engine seeks when it holds the book. A not-prepared book moves its pending start
     * (Decision 5). A finished book plays again from there, like Listen again (§4.5, Book Detail §2.5).
     */
    fun seekTo(positionMs: Long) {
        val loaded = loadedOrNull() ?: return
        val target = positionMs.coerceAtLeast(0L)
        when {
            loaded.phase is PlayerPhase.Finished -> startLoad(loaded.bookId, target)
            loaded.isEngineBound -> engine.seekTo(target)
            loaded.phase !is PlayerPhase.Preparing -> pendingStartMs.value = target
        }
    }

    /** Play at [speed], clamped to 0.5–3.0, as this book's own rate. Only while the engine holds the book. */
    fun setSpeed(speed: Float) {
        if (engineBound.value) engine.setSpeed(speed.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX))
    }

    /** ±0.05 within 0.5–3.0, with no float drift (§4.3, Q3). */
    fun stepSpeed(up: Boolean) {
        setSpeed(stepPlaybackSpeed(speed.value, up))
    }

    /** Back to the user's default rate; this book stops being an exception. */
    fun resetSpeed() {
        if (!engineBound.value) return
        scope.launch { engine.resetSpeed(playbackPreferences.getDefaultPlaybackSpeed()) }
    }

    /** Boost by [boostDb] as this book's own. */
    fun setBoost(boostDb: Float) {
        if (engineBound.value) engine.setBoost(boostDb)
    }

    /** Back to the user's default boost. */
    fun resetBoost() {
        if (!engineBound.value) return
        scope.launch { engine.resetBoost(playbackPreferences.getDefaultVolumeBoostDb()) }
    }

    // ── Errors (Task 16) ─────────────────────────────────────────────────────────────────────────────

    /**
     * Retry a failed or unavailable book (§4.3): re-probe the server when the cause is transport, then
     * re-prepare at the saved position and play. Held, revoked and session-expired books are not retried here:
     * their primary control leads elsewhere (Open Inbox, Close, Sign in).
     */
    fun retry() {
        val loaded = loadedOrNull() ?: return
        val cause =
            (loaded.phase as? PlayerPhase.Failed)?.error
                ?: (loaded.phase as? PlayerPhase.Unavailable)?.reason
                ?: return
        if (cause is BookError.HeldForReview || cause is PlaybackError.NoLongerAvailable ||
            cause is AuthError.SessionExpired
        ) {
            return
        }
        if (cause.isTransport()) scope.launch { serverReachability.retry() }
        engine.clearError()
        startLoad(loaded.bookId, null)
    }

    /**
     * Dismiss the error card (gap 6): the bar never hides. A failed prepare returns to Restored (Decision 6);
     * an engine failure mid-play clears and pauses.
     */
    fun dismissError() {
        val current = stage.value ?: return
        when {
            current is SessionStage.Failed -> {
                stage.value = SessionStage.Idle(current.bookId)
            }

            engine.error.value != null -> {
                engine.clearError()
                engine.pause()
            }
        }
    }

    private fun AppError.isTransport(): Boolean =
        this is PlaybackError.ConnectionLost ||
            this is PlaybackError.ServerUnreachable ||
            this is PlaybackError.Stalled ||
            this is PlaybackError.OfflineNotDownloaded

    // ── Sleep (Task 17) ──────────────────────────────────────────────────────────────────────────────

    /** Start a timer. A duration takes any whole minutes from 1 to 720 (the custom stepper, PT8). */
    fun setSleepTimer(mode: SleepTimerMode) {
        val bounded =
            if (mode is SleepTimerMode.Duration) {
                SleepTimerMode.Duration(mode.minutes.coerceIn(1, MAX_SLEEP_MINUTES))
            } else {
                mode
            }
        sleepTimer.setTimer(bounded)
    }

    /** +5 / +10 / +15 (and the notification's +10). */
    fun extendSleepTimer(minutes: Int) {
        sleepTimer.extendTimer(minutes)
    }

    /** Cancel the timer. During the fade this is "Keep listening": the fade stops and full volume returns (PT7). */
    fun cancelSleepTimer() {
        val wasFading = sleepTimer.state.value is SleepTimerState.FadingOut
        fadeJob?.cancel()
        sleepTimer.cancelTimer()
        if (wasFading) engine.setVolume(1f)
    }

    private suspend fun runFade() {
        val job =
            scope.launch {
                try {
                    fadeOutAndPause(setVolume = engine::setVolume, pause = engine::pause)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Sleep fade failed; resetting the timer" }
                } finally {
                    // Never leave the timer stuck in FadingOut, even when the fade throws or is cancelled.
                    sleepTimer.onFadeCompleted()
                }
            }
        fadeJob = job
        job.join()
    }
}
