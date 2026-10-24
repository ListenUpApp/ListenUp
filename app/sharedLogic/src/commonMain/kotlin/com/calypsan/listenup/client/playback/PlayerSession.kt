package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
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
import com.calypsan.listenup.client.presentation.player.skipDistanceMs
import com.calypsan.listenup.client.presentation.player.skipTargetMs
import com.calypsan.listenup.client.presentation.player.stepPlaybackSpeed
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
@Suppress("LongParameterList") // DI constructor: each param is a distinct domain responsibility.
class PlayerSession(
    private val engine: PlayerEngine,
    private val loadedBooks: LoadedBookRepository,
    bookRepository: BookRepository,
    downloadRepository: DownloadRepository,
    private val positionRepository: PlaybackPositionRepository,
    availability: BookAvailability,
    inboxRepository: InboxRepository,
    networkMonitor: NetworkMonitor,
    private val serverReachability: ServerReachability,
    authSession: AuthSession,
    private val sleepTimer: SleepTimerManager,
    private val playbackPreferences: PlaybackPreferences,
    /** True where the device keeps downloads; the browser does not, so it never says "offline, not downloaded". */
    val offlineCopiesSupported: Boolean,
    private val scope: CoroutineScope,
) : LoadedBookPlayhead {
    private val stage = MutableStateFlow<SessionStage?>(null)
    private val restoring = MutableStateFlow(true)
    private var generation = 0
    private var loadJob: Job? = null
    private val sleep = PlayerSleep(engine = engine, sleepTimer = sleepTimer, scope = scope)

    /** The book a load is in flight for, set synchronously so a double tap is swallowed in the same tick. */
    val preparingBookId: StateFlow<BookId?>
        field = MutableStateFlow<BookId?>(null)

    /** [preparingBookId] behind a 250 ms debounce, for buttons that must not flash on a fast prepare (Decision 11). */
    val preparingBookIdUi: Flow<BookId?> = preparingBookIdUiFlow(preparingBookId)

    /** Where a not-prepared book will start when Play is pressed (Decision 5): picked, moved from, or the saved place. */
    private val pendingStart = MutableStateFlow<PlaybackStart>(PlaybackStart.Resume)

    private val readout =
        PlayerReadout(
            engine = engine,
            stage = stage,
            pendingStart = pendingStart,
            bookRepository = bookRepository,
            downloadRepository = downloadRepository,
            positionRepository = positionRepository,
            playbackPreferences = playbackPreferences,
            scope = scope,
        )
    private val stageBookId: Flow<BookId?> get() = readout.stageBookId
    private val engineBound: StateFlow<Boolean> get() = readout.engineBound

    /** Where a not-prepared book shows it will start (Decision 5), or null for the saved position. */
    val pendingStartMs: StateFlow<Long?> get() = readout.pendingStartMs

    /** Book-relative playhead: the engine's while it holds the book, else the pending start or the saved position. Ticks. */
    val positionMs: StateFlow<Long> get() = readout.positionMs

    override val playhead: StateFlow<BookPlayhead?> get() = readout.playhead

    /** The loaded book's chapters: the engine's prepared list, or Room's before a prepare. */
    val chapters: StateFlow<List<Chapter>> get() = readout.chapters

    /** The loaded book's duration, in ms. */
    val durationMs: StateFlow<Long> get() = readout.durationMs

    /** The current chapter, from the one derivation ([chapterInfoAt]) whichever side holds the book. */
    val currentChapter: StateFlow<PlaybackManager.ChapterInfo?> get() = readout.currentChapter

    /** The rate: the engine's, else this book's saved rate, else the default. */
    val speed: StateFlow<Float> get() = readout.speed

    /** The boost in dB: the engine's, else this book's saved boost, else the default. */
    val boostDb: StateFlow<Float> get() = readout.boostDb

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

    /** The finish and start instants on [bookId]'s position row (the finished state's "6 Sep – 10 Oct"). */
    fun finishDates(bookId: BookId): Flow<Pair<Long?, Long?>> =
        positionRepository.observe(bookId).map { it?.finishedAtMs to it?.startedAtMs }.distinctUntilChanged()

    private val derivation =
        PlayerStateDerivation(
            engine = engine,
            stage = stage,
            restoring = restoring,
            stageBookId = stageBookId,
            positionRepository = positionRepository,
            availability = availability,
            inboxRepository = inboxRepository,
            networkMonitor = networkMonitor,
            authSession = authSession,
            sleepTimer = sleepTimer,
            offlineCopiesSupported = offlineCopiesSupported,
            scope = scope,
        )

    /** The session's one state. Never re-emits on a position tick. */
    val state: StateFlow<PlayerSessionState> = derivation.state

    init {
        scope.launch { loadedBooks.observeLoadedBookId().collect(::onPersistedBookId) }
        scope.launch { engine.loadedBookId.collect(::onEngineBook) }
        scope.launch { watchLiveness() }
        scope.launch { haltSurfacedFailures() }
        sleep.start(loadedBookId = stageBookId)
    }

    // ── Lifecycle (Task 13) ──────────────────────────────────────────────────────────────────────────

    /** Key clears issued by [closeBook] that have not landed yet. Touched only on the player scope. */
    private var closeClearsInFlight = 0

    private fun onPersistedBookId(persisted: BookId?) {
        val current = stage.value
        when {
            persisted == null && closeClearsInFlight > 0 -> {
                // Stale: Close already unloaded in memory, and its clear writes back any book loaded since.
            }

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
                pendingStart.value = PlaybackStart.Resume
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
                pendingStart.value = PlaybackStart.Resume
                scope.launch { loadedBooks.setLoaded(held) }
            }

            current is SessionStage.Idle || current is SessionStage.Failed -> {
                // The engine started this book itself (a headset Play, Media3 resumption): it plays, so a failed
                // prepare's error no longer stands.
                stage.value = SessionStage.Engine(held)
                pendingStart.value = PlaybackStart.Resume
            }
        }
    }

    private suspend fun watchLiveness() {
        readout.liveness.collect { (_, verdict) ->
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
        onPlayer {
            cancelLoad()
            sleep.close()
            engine.stop()
            unloadInMemory()
            closeClearsInFlight++
            scope.launch {
                try {
                    loadedBooks.clear()
                    // A book loaded while the clear was being written (Close, then Play at once) is the loaded
                    // book: the clear must not leave the device thinking nothing is loaded.
                    stage.value?.let { loadedBooks.setLoaded(it.bookId) }
                } finally {
                    closeClearsInFlight--
                }
            }
        }
    }

    private fun unloadInMemory() {
        cancelLoad()
        stage.value = null
        pendingStart.value = PlaybackStart.Resume
    }

    private fun cancelLoad() {
        val wasLoading = loadJob?.isActive == true
        loadJob?.cancel()
        loadJob = null
        generation++
        preparingBookId.value = null
        if (wasLoading) engine.abandonLoad()
    }

    // ── Load (Task 14) ───────────────────────────────────────────────────────────────────────────────

    /**
     * Play [bookId] (the play entry for every surface and Home, P-R5). [startAtMs] starts there (book ms);
     * null resumes from the saved position.
     *
     * - A plain repeat tap for the book already preparing is swallowed (a double tap).
     * - The book the engine already holds, not finished, not failed and not halted by a failure, is a resume: a
     *   seek when [startAtMs] is given, then play. Never a re-prepare (the web rule, now everyone's).
     * - Anything else starts a load, which supersedes any load in flight. A plain Play of the loaded book starts
     *   where the listener moved it before Play.
     */
    fun playBook(
        bookId: BookId,
        startAtMs: Long?,
    ) {
        onPlayer {
            if (startAtMs == null && preparingBookId.value == bookId) return@onPlayer
            val loaded = loadedOrNull()
            val resumable =
                loaded != null &&
                    loaded.bookId == bookId &&
                    loaded.isEngineBound &&
                    loaded.phase !is PlayerPhase.Finished &&
                    loaded.phase !is PlayerPhase.Failed &&
                    (stage.value as? SessionStage.Engine)?.halted != true
            if (resumable) {
                if (startAtMs != null) engine.seekTo(startAtMs.coerceIn(0L, (durationMs.value - 1).coerceAtLeast(0L)))
                engine.clearError()
                engine.play()
                return@onPlayer
            }
            // A plain Play of the loaded, not-prepared book honours where the listener moved it (Decision 5).
            val pending = pendingStart.value.takeIf { stage.value?.bookId == bookId }
            startLoad(bookId, startAtMs?.let(PlaybackStart::At) ?: pending ?: PlaybackStart.Resume)
        }
    }

    /**
     * Load [bookId] at [start]. Synchronous up to the engine's first suspension, so the bar names the new book
     * (§3.6) and a browser's gesture is spent ([PlayerEngine.beginLoad]) inside the tap. Only the newest
     * generation may finish: a superseded load's `finally` cannot touch its successor's state.
     */
    private fun startLoad(
        bookId: BookId,
        start: PlaybackStart,
    ) {
        cancelLoad()
        // A fade belongs to the book it was started for: left running, it would pause this one.
        if (stage.value?.bookId != bookId) sleep.cancelFade()
        // cancelLoad() moved the generation on, so this load's generation is read after it.
        val myGeneration = generation
        preparingBookId.value = bookId
        stage.value = SessionStage.Preparing(bookId, myGeneration)
        pendingStart.value = PlaybackStart.Resume
        sleep.onBookChanged(bookId)
        engine.beginLoad(bookId)
        loadJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var handedOver = false
                try {
                    loadedBooks.setLoaded(bookId)
                    val result = engine.load(bookId, start)
                    // A failure while the book still has a proactive reason (offline and not downloaded, server
                    // unreachable, session lapsed) is that reason, not a generic one: back to Idle, which shows it
                    // and clears it when the cause does.
                    val failedForAKnownReason =
                        result is AppResult.Failure && derivation.unavailableReason(bookId).first() != null
                    if (myGeneration != generation) return@launch
                    stage.value =
                        when (result) {
                            is AppResult.Success -> {
                                handedOver = true
                                applySeekQueuedWhilePreparing()
                                SessionStage.Engine(bookId)
                            }

                            is AppResult.Failure -> {
                                if (failedForAKnownReason) {
                                    SessionStage.Idle(bookId)
                                } else {
                                    SessionStage.Failed(bookId, result.error)
                                }
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

    /** A scrub made while the book prepared ([seekTo]) lands on the engine now that it holds the book. */
    private fun applySeekQueuedWhilePreparing() {
        val queued = pendingStart.value as? PlaybackStart.At ?: return
        pendingStart.value = PlaybackStart.Resume
        val lastMs = engine.durationMs.value - 1
        engine.seekTo(if (lastMs > 0) queued.positionMs.coerceIn(0L, lastMs) else queued.positionMs)
    }

    /**
     * Run [command] confined to the player scope: inline when the caller is already on its thread, so a tap's
     * synchronous work (a browser's gesture, a double tap swallowed in the same tick) happens inside the call;
     * otherwise hopped onto the scope. Sign-out, for one, calls Close from a background scope.
     */
    private inline fun onPlayer(crossinline command: () -> Unit) {
        if (isOnPlayerThread()) command() else scope.launch { command() }
    }

    private fun isOnPlayerThread(): Boolean {
        val context = scope.coroutineContext
        context[PlayerThreadCheck]?.let { return it.isCurrent() }
        val dispatcher = context[ContinuationInterceptor] as? CoroutineDispatcher ?: return true
        return !dispatcher.isDispatchNeeded(context)
    }

    private fun loadedOrNull(): PlayerSessionState.Loaded? = state.value as? PlayerSessionState.Loaded

    // ── Transport (Task 15) ──────────────────────────────────────────────────────────────────────────

    /**
     * The one play/pause intent (§4.3): resume, pause, prepare a restored book, re-listen, or retry. A pause left by
     * a dismissed failure prepares the book again: the failure halted the engine, and on the web it tore the element's
     * source down, so resuming it would play nothing.
     */
    fun playPause() {
        onPlayer {
            val loaded = loadedOrNull() ?: return@onPlayer
            when (val phase = loaded.phase) {
                PlayerPhase.Playing, PlayerPhase.SleepFading -> {
                    engine.pause()
                }

                PlayerPhase.Buffering -> {
                    pauseBuffering(isReconnecting = false)
                }

                PlayerPhase.Reconnecting -> {
                    pauseBuffering(isReconnecting = true)
                }

                PlayerPhase.Paused -> {
                    engine.clearError()
                    if ((stage.value as? SessionStage.Engine)?.halted == true) {
                        startLoad(loaded.bookId, retryStart(loaded))
                    } else {
                        engine.play()
                    }
                }

                PlayerPhase.Restored -> {
                    startLoad(loaded.bookId, pendingStart.value)
                }

                PlayerPhase.Finished -> {
                    startLoad(loaded.bookId, PlaybackStart.Resume)
                }

                is PlayerPhase.Failed -> {
                    if (phase.error is PlaybackError.AutoplayBlocked ||
                        phase.error is PlaybackError.BlockedInBackground
                    ) {
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
    }

    /**
     * Pause a stalled book. Reconnecting, or with a failure the engine reported, the engine is recovering from a
     * network drop inside its grace; a pause there ends the drop as a pause, never an error later. The engine is told
     * to stop recovering and forget the failure, and Play prepares the book again (the engine may have let go of its
     * audio, as the web and iOS do, or be between two of its own fresh prepares).
     */
    private fun pauseBuffering(isReconnecting: Boolean) {
        val current = stage.value
        if ((isReconnecting || engine.error.value != null) && current is SessionStage.Engine) {
            engine.clearError()
            engine.pause()
            stage.value = current.copy(halted = true, failure = null)
        } else {
            engine.pause()
        }
    }

    /** Back by the user's interval × speed, clamped (§3.3). */
    fun skipBack() {
        onPlayer {
            skipBy(skipBackwardSec.value, forward = false)
        }
    }

    /** Forward by the user's interval × speed, clamped. */
    fun skipForward() {
        onPlayer {
            skipBy(skipForwardSec.value, forward = true)
        }
    }

    private fun skipBy(
        seconds: Int,
        forward: Boolean,
    ) {
        val loaded = loadedOrNull() ?: return
        if (loaded.phase is PlayerPhase.Finished || loaded.phase is PlayerPhase.Preparing) return
        val duration = durationMs.value
        if (duration <= 0) return
        if (loaded.isEngineBound) {
            seekTo(
                skipTargetMs(
                    currentPositionMs = positionMs.value,
                    seconds = seconds,
                    speed = speed.value,
                    totalDurationMs = duration,
                    forward = forward,
                ),
            )
        } else {
            val distanceMs = skipDistanceMs(seconds, speed.value)
            carry(PlaybackMove.Skip(if (forward) distanceMs else -distanceMs))
        }
    }

    /** §3.3: restart the chapter after 3 s, else the previous one; chapter 1 restarts. */
    fun previousChapter() {
        onPlayer {
            if (movesThePlayhead()) {
                seekTo(previousChapterTargetMs(positionMs.value, chapters.value))
            } else {
                carry(PlaybackMove.PreviousChapter)
            }
        }
    }

    /** §3.3: the next chapter's start; a no-op at the last chapter. */
    fun nextChapter() {
        onPlayer {
            if (movesThePlayhead()) {
                nextChapterTargetMs(positionMs.value, chapters.value)?.let(::seekTo)
            } else {
                carry(PlaybackMove.NextChapter)
            }
        }
    }

    /** Whether a relative move seeks now: the engine holds the book, or it is finished (a move re-listens from there). */
    private fun movesThePlayhead(): Boolean {
        val loaded = loadedOrNull() ?: return false
        return loaded.isEngineBound || loaded.phase is PlayerPhase.Finished
    }

    /**
     * A relative move on a book that is not prepared: carried to Play, which replays it from the reconciled saved
     * position. Measured from this device's row alone it could resume from a stale place and overwrite another
     * device's newer progress (position is sacred).
     */
    private fun carry(move: PlaybackMove) {
        val loaded = loadedOrNull() ?: return
        if (loaded.phase is PlayerPhase.Preparing) return
        pendingStart.value = pendingStart.value.then(move, readout.roomChapters.value, readout.roomDurationMs.value)
    }

    /** The start of chapter [index]; an index the list no longer has is ignored, never clamped. */
    fun seekToChapter(index: Int) {
        onPlayer {
            chapters.value.getOrNull(index)?.let { seekTo(it.startTime) }
        }
    }

    /** [fraction] of the current chapter (the whole book when it has no chapters). */
    fun seekWithinChapter(fraction: Float) {
        onPlayer {
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
    }

    /**
     * Move the playhead. The engine seeks when it holds the book. A not-prepared book moves its pending start
     * (Decision 5); a book still preparing keeps it and seeks there when the load lands. A finished book plays
     * again from there, like Listen again (§4.5, Book Detail §2.5).
     */
    fun seekTo(positionMs: Long) {
        onPlayer {
            val loaded = loadedOrNull() ?: return@onPlayer
            val target = positionMs.coerceAtLeast(0L)
            when {
                loaded.phase is PlayerPhase.Finished -> startLoad(loaded.bookId, PlaybackStart.At(target))
                loaded.isEngineBound -> engine.seekTo(target)
                else -> pendingStart.value = PlaybackStart.At(target)
            }
        }
    }

    /** Play at [speed], clamped to 0.5–3.0, as this book's own rate. Only while the engine holds the book. */
    fun setSpeed(speed: Float) {
        onPlayer {
            if (engineBound.value) engine.setSpeed(speed.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX))
        }
    }

    /** ±0.05 within 0.5–3.0, with no float drift (§4.3, Q3). */
    fun stepSpeed(up: Boolean) {
        onPlayer {
            setSpeed(stepPlaybackSpeed(speed.value, up))
        }
    }

    /** Back to the user's default rate; this book stops being an exception. */
    fun resetSpeed() {
        onPlayer {
            if (!engineBound.value) return@onPlayer
            scope.launch { engine.resetSpeed(playbackPreferences.getDefaultPlaybackSpeed()) }
        }
    }

    /** Boost by [boostDb] as this book's own. */
    fun setBoost(boostDb: Float) {
        onPlayer {
            if (engineBound.value) engine.setBoost(boostDb)
        }
    }

    /** Back to the user's default boost. */
    fun resetBoost() {
        onPlayer {
            if (!engineBound.value) return@onPlayer
            scope.launch { engine.resetBoost(playbackPreferences.getDefaultVolumeBoostDb()) }
        }
    }

    // ── Errors (Task 16) ─────────────────────────────────────────────────────────────────────────────

    /**
     * Retry a failed or unavailable book (§4.3): re-probe the server when the cause is transport, then
     * re-prepare and play from where the listener sees the book ([retryStart]). Held, revoked and session-expired
     * books are not retried here: their primary control leads elsewhere (Open Inbox, Close, Sign in).
     */
    fun retry() {
        onPlayer {
            val loaded = loadedOrNull() ?: return@onPlayer
            val cause =
                (loaded.phase as? PlayerPhase.Failed)?.error
                    ?: (loaded.phase as? PlayerPhase.Unavailable)?.reason
                    ?: return@onPlayer
            if (cause is BookError.HeldForReview || cause is PlaybackError.NoLongerAvailable ||
                cause is AuthError.SessionExpired
            ) {
                return@onPlayer
            }
            if (cause.isTransport()) scope.launch { serverReachability.retry() }
            val start = retryStart(loaded)
            engine.clearError()
            startLoad(loaded.bookId, start)
        }
    }

    /**
     * Where a Retry starts: the place the listener sees, including any move made while the book was failed. A
     * failed engine may not hold that place itself (the web tears hls.js down) and the move was never saved, so
     * a re-prepare at the saved position would throw it away. An engine-bound failure starts at the engine's
     * position, unless that is 0 (a reset player, never a reason to lose the saved place); a failed prepare
     * replays its pending start, like Play.
     */
    private fun retryStart(loaded: PlayerSessionState.Loaded): PlaybackStart =
        if (loaded.isEngineBound) {
            positionMs.value.takeIf { it > 0 }?.let(PlaybackStart::At) ?: PlaybackStart.Resume
        } else {
            pendingStart.value
        }

    /**
     * Dismiss the error card (gap 6): the bar never hides. A failed prepare returns to Restored (Decision 6);
     * an engine failure mid-play clears and pauses.
     */
    fun dismissError() {
        onPlayer {
            val current = stage.value ?: return@onPlayer
            when {
                current is SessionStage.Failed -> {
                    stage.value = SessionStage.Idle(current.bookId)
                }

                current is SessionStage.Engine && current.failure != null -> {
                    stage.value = current.copy(failure = null)
                    engine.clearError()
                    engine.pause()
                }

                engine.error.value != null -> {
                    engine.clearError()
                    engine.pause()
                }
            }
        }
    }

    /**
     * Nothing plays while the bar says it's broken. Once a failure surfaces, the engine is told to stop trying: a
     * pause, which every engine treats as the end of its own recovery (Media3's re-prepare, hls.js's retries, the
     * iOS engine's fresh prepare). Only Retry or an explicit Play starts it again; a Play from the pause a Dismiss
     * leaves prepares the book again. A network drop that interrupts a listen surfaces only after
     * [PLAYBACK_DROP_GRACE] (the derivation shows Reconnecting until then), so a short one recovers without an error. A
     * failure the session surfaced with no engine error behind it is kept on the stage, so the halt goes on saying
     * why. An engine heard playing again (an autoplay block lifted) is no longer halted.
     */
    private suspend fun haltSurfacedFailures() {
        state
            .map { current -> (current as? PlayerSessionState.Loaded)?.takeIf { it.isEngineBound } }
            .distinctUntilChanged()
            .collect { bound ->
                val current = stage.value
                if (bound == null || current !is SessionStage.Engine || current.bookId != bound.bookId) return@collect
                val playing = bound.phase == PlayerPhase.Playing || bound.phase == PlayerPhase.SleepFading
                if (bound.phase is PlayerPhase.Failed) {
                    engine.pause()
                    val ownFailure = bound.phase.error.takeIf { engine.error.value == null }
                    stage.value = current.copy(halted = true, failure = ownFailure ?: current.failure)
                } else if (playing && current.halted) {
                    stage.value = current.copy(halted = false, failure = null)
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
        onPlayer { sleep.set(mode) }
    }

    /** +5 / +10 / +15 (and the notification's +10). */
    fun extendSleepTimer(minutes: Int) {
        onPlayer { sleep.extend(minutes) }
    }

    /** Cancel the timer. During the fade this is "Keep listening": the fade stops and full volume returns (PT7). */
    fun cancelSleepTimer() {
        onPlayer { sleep.cancel() }
    }
}
