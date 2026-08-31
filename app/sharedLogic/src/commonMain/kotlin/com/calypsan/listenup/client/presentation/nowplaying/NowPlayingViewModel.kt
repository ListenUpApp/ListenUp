
package com.calypsan.listenup.client.presentation.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.ContributorPickerType
import com.calypsan.listenup.client.playback.NowPlayingOverlay
import com.calypsan.listenup.client.playback.NowPlayingScreenState
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackDynamics
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackProgress
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SurfaceMetadata
import com.calypsan.listenup.client.playback.fadeOutAndPause
import com.calypsan.listenup.client.playback.mapToNowPlayingState
import com.calypsan.listenup.client.playback.mapToPlaybackProgress
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for Now Playing UI (mini player and full screen).
 *
 * Composes [PlaybackManager] flows + book metadata + UI ephemera into a single
 * [NowPlayingScreenState] via layered `combine().stateIn(WhileSubscribed)`. The
 * heavy upstream pipeline ([nowPlayingState]) is independent of overlay/expand
 * ephemera, which join only at the screen-state boundary so picker toggles do
 * not re-execute the upstream combine chain.
 *
 * All command-side operations route through [PlaybackController].
 *
 * Lifecycle: a plain Koin `factory` — `koinViewModel()` scopes a fresh instance to each owning
 * `ViewModelStore` (the shell's Activity-scoped store and the document viewer's nav-entry-scoped
 * store), exactly like every other ViewModel. The two process-lifetime pieces that used to force
 * this VM into being the app's sole `single` ViewModel now live outside it:
 * [PlaybackController] acquisition happens once at Koin startup via
 * `PlaybackControllerActivator`, and expand/collapse state lives in [sheetState] so every
 * instance reads and writes the same expansion flag. A store clearing only cancels that store's
 * instance; the next `koinViewModel()` builds a fresh one over these live singletons, so playback
 * command state, expansion state, and the controller connection are all correct immediately —
 * no re-served zombie instance with a permanently-cancelled `viewModelScope`.
 *
 * Exposes ~25 distinct, intent-named user actions (transport, chapter, speed,
 * sleep-timer, sheet visibility), each mapping to a single UI affordance; merging
 * them to satisfy the function-count metric would reduce call-site clarity, hence
 * the narrow [Suppress] (replaces the former file-level suppression).
 */
@Suppress("TooManyFunctions")
class NowPlayingViewModel internal constructor(
    private val playbackManager: PlaybackManager,
    private val bookRepository: BookRepository,
    private val sleepTimerManager: SleepTimerManager,
    private val playbackController: PlaybackController,
    private val playbackPreferences: PlaybackPreferences,
    private val networkMonitor: NetworkMonitor,
    private val documentRepository: DocumentRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val sheetState: NowPlayingSheetState,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }

    private val overlayFlow = MutableStateFlow<NowPlayingOverlay>(NowPlayingOverlay.None)

    private val _navActions = Channel<NowPlayingNavAction>(Channel.BUFFERED)

    /** One-shot navigation events — the host collects these to trigger platform navigation. */
    val navActions: Flow<NowPlayingNavAction> = _navActions.receiveAsFlow()

    /**
     * The id of the first PDF document for the currently-playing book, or null when the book has
     * none. Switches automatically when the playing book changes via [flatMapLatest].
     *
     * Used to gate the "Open PDF" overflow menu item in [PlayerTopBar].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val firstPdfDocId: StateFlow<String?> =
        playbackManager.currentBookId
            .flatMapLatest { bookId ->
                if (bookId == null) {
                    flowOf(null)
                } else {
                    documentRepository
                        .observeDocuments(bookId)
                        .map { docs -> docs.firstOrNull { it.format == "pdf" }?.id }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = null,
            )

    /** Reactive book metadata for the current book id. One-shot fetch on bookId change via flatMapLatest. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val bookFlow: Flow<BookListItem?> =
        playbackManager.currentBookId.flatMapLatest { bookId ->
            flow {
                emit(loadBook(bookId))
            }
        }

    /** Slow-changing play state (no position) — feeds the position-free [Active] combine. */
    private val playStateFlow: Flow<PlaybackDynamics> =
        combine(
            playbackManager.isPlaying,
            playbackManager.isBuffering,
            playbackManager.playbackSpeed,
            playbackManager.volumeBoostDb,
        ) { isPlaying, isBuffering, speed, boostDb ->
            PlaybackDynamics(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                playbackSpeed = speed,
                volumeBoostDb = boostDb,
            )
        }

    /** Aggregated surface metadata (chapter info + chapter list + error + defaults for speed/boost). */
    private val surfaceMetadataFlow: Flow<SurfaceMetadata> =
        combine(
            playbackManager.currentChapter,
            playbackManager.chapters,
            playbackManager.playbackError,
            playbackPreferences.observeDefaultPlaybackSpeed(),
            playbackPreferences.observeDefaultVolumeBoostDb(),
        ) { chapter, chapters, error, defaultSpeed, defaultBoostDb ->
            SurfaceMetadata(
                currentChapter = chapter,
                chapters = chapters,
                error = error,
                defaultPlaybackSpeed = defaultSpeed,
                defaultVolumeBoostDb = defaultBoostDb,
            )
        }

    /** Sealed playback state derived from book + dynamics + metadata via the pure mapper. */
    private val nowPlayingState: Flow<NowPlayingState> =
        combine(
            bookFlow,
            playStateFlow,
            surfaceMetadataFlow,
        ) { book, dynamics, metadata ->
            mapToNowPlayingState(book = book, dynamics = dynamics, metadata = metadata)
        }

    /** Tail-combined screen state; the only flow the UI subscribes to. */
    val screenState: StateFlow<NowPlayingScreenState> =
        combine(
            nowPlayingState,
            overlayFlow,
            sheetState.isExpanded,
            sleepTimerManager.state,
            playbackManager.preparingBookIdUi,
        ) { state, overlay, isExpanded, sleepTimer, preparingBookIdUi ->
            NowPlayingScreenState(
                state = state,
                overlay = overlay,
                isExpanded = isExpanded,
                sleepTimerState = sleepTimer,
                isPlayPending = preparingBookIdUi != null,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue =
                NowPlayingScreenState(
                    state = NowPlayingState.Idle,
                    overlay = NowPlayingOverlay.None,
                    // Read the live singleton's current value (not a hardcoded false) so a fresh
                    // VM instance's screenState.value is correct even before the combine above
                    // has had a chance to emit — e.g. a second koinViewModel() instance created
                    // while the sheet is already expanded from the first instance's perspective.
                    isExpanded = sheetState.isExpanded.value,
                    sleepTimerState = sleepTimerManager.state.value,
                    // preparingBookIdUi is a plain (cold, debounced) Flow with no .value — seed from
                    // the instant preparingBookId StateFlow instead. This can seed `true` slightly
                    // before the real, debounced combine value would (skipping the debounce grace
                    // period for this one synchronous read) — an acceptable "fail visible" bias: a
                    // fresh VM instance built while a prepare is genuinely already in flight must
                    // never seed false and mask it, and the combine's first emission corrects this
                    // within one collection regardless.
                    isPlayPending = playbackManager.preparingBookId.value != null,
                ),
        )

    /**
     * How far a forward transport skip moves, in seconds — the user's synced Settings value.
     *
     * Read by [skipForward] and rendered by the transport controls (icon + content description),
     * so the button, its label and the seek can never disagree.
     *
     * `Eagerly`, not `WhileSubscribed`: this is a *command* input as much as screen state, and
     * [skipForward] reads `.value` whether or not anything happens to be collecting this
     * particular flow. Under `WhileSubscribed` an unsubscribed moment would silently serve the
     * stock 30 — which is precisely the bug this exists to fix. Two Ints off a Room-backed flow
     * cost nothing to keep hot.
     */
    val skipForwardSec: StateFlow<Int> =
        playbackPreferences
            .observeDefaultSkipForwardSec()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
            )

    /** How far a backward transport skip moves, in seconds. See [skipForwardSec] for the sharing rationale. */
    val skipBackwardSec: StateFlow<Int> =
        playbackPreferences
            .observeDefaultSkipBackwardSec()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
            )

    /**
     * Fast-changing playback progress. Split from [screenState] so a position tick
     * re-emits only this — not the whole player state. The seekbar + time labels
     * subscribe here; the player layout subscribes to [screenState].
     */
    val progress: StateFlow<PlaybackProgress> =
        combine(
            playbackManager.currentPositionMs,
            playbackManager.totalDurationMs,
            playbackManager.currentChapter,
        ) { position, duration, chapter ->
            mapToPlaybackProgress(position, duration, chapter)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = PlaybackProgress.Zero,
        )

    init {
        // Side effect: notify SleepTimerManager when the chapter index changes
        // (drives end-of-chapter sleep timer). Distinct-by-index dedupes within the
        // flow rather than via a private var.
        viewModelScope.launch {
            playbackManager.currentChapter
                .filterNotNull()
                .distinctUntilChangedBy { it.index }
                .collect { chapterInfo ->
                    sleepTimerManager.onChapterChanged(chapterInfo.index)
                }
        }

        // Side effect: tear down the player when the currently-playing book leaves the local mirror
        // (a removal tombstone or an access revoke), with ONE offline-grace exception — a DOWNLOADED,
        // IN-PROGRESS copy keeps playing (never-stranded). A streaming (not-downloaded) book stops
        // (the server already 404s its stream); a downloaded-but-not-in-progress book also drops.
        watchNowPlayingLiveness()

        // Side effect: handle sleep timer fade-out events.
        // The `finally` ensures onFadeCompleted() (which resets state to Inactive) is
        // always called — even when the fade throws or when viewModelScope is
        // cancelled mid-fade. Without this, a thrown exception leaves the timer stuck
        // in FadingOut, which causes PlaybackService to apply the short sleep-idle
        // timeout to every subsequent pause.
        viewModelScope.launch {
            sleepTimerManager.sleepEvent.collect {
                try {
                    fadeOutAndPause(playbackController)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "fadeOutAndPause failed — resetting sleep timer to Inactive" }
                } finally {
                    sleepTimerManager.onFadeCompleted()
                }
            }
        }
    }

    /**
     * Observe the now-playing book's liveness in the local mirror and tear the player down when the
     * book is gone (removed or access-revoked) — honouring the offline-grace exception.
     *
     * The decision per book: tear down when the book is NOT live AND it is NOT the protected case of
     * being both downloaded AND in-progress. A streaming book (not downloaded) stops the moment access
     * is lost; a downloaded book the user has meaningfully started keeps playing offline; a downloaded
     * book at position 0 (never really started) drops. [closeBook] resets `currentBookId`, so the
     * [flatMapLatest] re-subscribes to `null` and the effect quiesces — no teardown loop.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchNowPlayingLiveness() {
        viewModelScope.launch {
            playbackManager.currentBookId
                .flatMapLatest { bookId ->
                    if (bookId == null) {
                        flowOf(false)
                    } else {
                        combine(
                            bookRepository.observeIsBookLive(bookId.value),
                            downloadRepository.observeBookStatus(bookId),
                            playbackPositionRepository.observe(bookId),
                        ) { isLive, downloadStatus, position ->
                            val downloaded = downloadStatus is BookDownloadStatus.Completed
                            val inProgress = position != null && position.positionMs > 0 && !position.isFinished
                            !isLive && !(downloaded && inProgress)
                        }
                    }
                }.distinctUntilChanged()
                .collect { shouldTearDown ->
                    if (shouldTearDown) {
                        logger.info {
                            "Now-playing book left the local mirror (removed/revoked) and is not a " +
                                "downloaded in-progress copy — tearing down the player."
                        }
                        closeBook()
                    }
                }
        }
    }

    private suspend fun loadBook(bookId: BookId?): BookListItem? {
        if (bookId == null) return null
        return try {
            bookRepository.getBookListItem(bookId.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "getBookListItem failed for ${bookId.value}" }
            null
        }
    }

    // === UI ephemera setters ===

    fun expand() {
        sheetState.expand()
    }

    fun collapse() {
        sheetState.collapse()
    }

    fun showChapterPicker() {
        overlayFlow.value = NowPlayingOverlay.ChapterPicker
    }

    fun hideChapterPicker() {
        overlayFlow.value = NowPlayingOverlay.None
    }

    fun showSpeedPicker() {
        overlayFlow.value = NowPlayingOverlay.SpeedPicker
    }

    fun hideSpeedPicker() {
        overlayFlow.value = NowPlayingOverlay.None
    }

    fun showBoostPicker() {
        overlayFlow.value = NowPlayingOverlay.BoostPicker
    }

    fun hideBoostPicker() {
        overlayFlow.value = NowPlayingOverlay.None
    }

    fun showSleepTimer() {
        overlayFlow.value = NowPlayingOverlay.SleepTimer
    }

    fun hideSleepTimer() {
        overlayFlow.value = NowPlayingOverlay.None
    }

    fun showContributorPicker(type: ContributorPickerType) {
        overlayFlow.value = NowPlayingOverlay.ContributorPicker(type)
    }

    fun hideContributorPicker() {
        overlayFlow.value = NowPlayingOverlay.None
    }

    // === Sleep Timer ===

    fun setSleepTimer(mode: SleepTimerMode) {
        sleepTimerManager.setTimer(mode)
        hideSleepTimer()
    }

    fun cancelSleepTimer() {
        sleepTimerManager.cancelTimer()
    }

    fun extendSleepTimer(minutes: Int) {
        sleepTimerManager.extendTimer(minutes)
    }

    // === Book actions ===

    /**
     * Open the first PDF document for the currently-playing book.
     *
     * Downloads the file if not already cached, then emits [NowPlayingNavAction.OpenDocumentViewer]
     * with the local path. Emits nothing if there is no current book or no PDF document.
     */
    fun onOpenCurrentPdf() {
        val bookId = playbackManager.currentBookId.value ?: return
        val docId = firstPdfDocId.value ?: return
        viewModelScope.launch {
            when (val result = documentRepository.ensureLocal(bookId, docId)) {
                is AppResult.Success -> {
                    _navActions.trySend(NowPlayingNavAction.OpenDocumentViewer(result.data))
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error {
                        "Failed to open PDF $docId for book $bookId: ${result.error.message}"
                    }
                }
            }
        }
    }

    /**
     * Close the book entirely - stops playback and clears state.
     */
    fun closeBook() {
        playbackController.stop()
        playbackManager.clearPlayback()
        sleepTimerManager.cancelTimer()
        collapse()
    }

    // === Playback actions ===

    /** The prepare/activate/start-playback [Job] currently holding [PlaybackManager]'s pending-play
     * window open — see [playBook]. Held so a tap for a DIFFERENT book can cancel the stale one
     * instead of either being swallowed (the original bug) or racing it. */
    private var preparingJob: Job? = null

    /**
     * Start playback of [bookId].
     *
     * A repeat tap for the SAME book while its prepare is already in flight is swallowed:
     * [PlaybackManager.markPreparing] is called synchronously (before [viewModelScope.launch]) so
     * the guard below sees it on the very next call, even one dispatched in the same tick — closing
     * the TOCTOU gap a check-then-launch would otherwise leave.
     *
     * A tap for a DIFFERENT book while one is in flight is never dropped — that would just trade one
     * silent no-op for another. Instead it *supersedes*: [preparingJob] is cancelled, then a new
     * window opens for the new book. The stale job's `finally` still runs (cancellation unwinds the
     * `try` normally) — it only calls [PlaybackManager.clearPreparing] if [PlaybackManager.preparingBookId]
     * still names ITS book, never unconditionally. Without that ownership check, a superseded job's
     * cleanup could run after the new job's [PlaybackManager.markPreparing] and wipe the new window —
     * this is the exact race [preparingJob] and the check below exist to prevent.
     *
     * The window spans the whole prepare/activate/start-playback sequence and — for whichever job
     * still owns it — is always closed via [PlaybackManager.clearPreparing] in a `finally`, so a
     * thrown or failed prepare can never strand it open.
     */
    fun playBook(bookId: BookId) {
        if (playbackManager.preparingBookId.value == bookId) return
        preparingJob?.cancel()
        playbackManager.markPreparing(bookId)
        preparingJob =
            viewModelScope.launch {
                try {
                    val result = playbackManager.prepareForPlayback(bookId)
                    if (result == null) {
                        val message =
                            if (networkMonitor.isOnline()) {
                                "Failed to load book"
                            } else {
                                "Can't play this book offline. Download it first."
                            }
                        playbackManager.reportError(message, isRecoverable = true)
                        return@launch
                    }
                    playbackManager.activateBook(bookId)
                    playbackController.startPlayback(result)
                } finally {
                    if (playbackManager.preparingBookId.value == bookId) {
                        playbackManager.clearPreparing()
                    }
                }
            }
    }

    fun playPause() {
        if (playbackManager.isPlaying.value) {
            playbackController.pause()
        } else {
            // Clear any latched error synchronously so the mini player (hidden while
            // NowPlayingState is Error) reappears the instant the user resumes, rather than
            // waiting for the async Playing-state confirmation from the platform player.
            playbackManager.clearError()
            playbackController.play()
        }
    }

    fun skipBack() {
        val seconds = skipBackwardSec.value
        logger.debug { "skipBack called: seconds=$seconds" }
        val timeline = playbackManager.currentTimeline.value
        if (timeline == null) {
            logger.warn { "skipBack: timeline is null" }
            return
        }

        val currentBookPos = playbackManager.currentPositionMs.value
        val newBookPos =
            skipTargetMs(
                currentPositionMs = currentBookPos,
                seconds = seconds,
                speed = playbackManager.playbackSpeed.value,
                totalDurationMs = playbackManager.totalDurationMs.value,
                forward = false,
            )
        logger.debug { "skipBack: currentPos=$currentBookPos, newPos=$newBookPos" }

        playbackController.seekTo(newBookPos)
        // Update PlaybackManager so UI updates immediately (even when paused)
        playbackManager.updatePosition(newBookPos)
    }

    fun skipForward() {
        val seconds = skipForwardSec.value
        logger.debug { "skipForward called: seconds=$seconds" }
        val timeline = playbackManager.currentTimeline.value
        if (timeline == null) {
            logger.warn { "skipForward: timeline is null" }
            return
        }

        val currentBookPos = playbackManager.currentPositionMs.value
        val totalDuration = playbackManager.totalDurationMs.value
        val newBookPos =
            skipTargetMs(
                currentPositionMs = currentBookPos,
                seconds = seconds,
                speed = playbackManager.playbackSpeed.value,
                totalDurationMs = totalDuration,
                forward = true,
            )
        logger.debug { "skipForward: currentPos=$currentBookPos, newPos=$newBookPos, totalDuration=$totalDuration" }

        playbackController.seekTo(newBookPos)
        // Update PlaybackManager so UI updates immediately (even when paused)
        playbackManager.updatePosition(newBookPos)
    }

    fun previousChapter() {
        val currentIndex = playbackManager.currentChapter.value?.index ?: 0
        logger.debug { "previousChapter called: current=$currentIndex" }
        val newIndex = (currentIndex - 1).coerceAtLeast(0)
        seekToChapter(newIndex)
    }

    fun nextChapter() {
        val currentIndex = playbackManager.currentChapter.value?.index ?: 0
        val chapters = playbackManager.chapters.value
        logger.debug { "nextChapter called: current=$currentIndex, total=${chapters.size}" }
        val newIndex = (currentIndex + 1).coerceAtMost(chapters.lastIndex.coerceAtLeast(0))
        seekToChapter(newIndex)
    }

    fun seekToChapter(index: Int) {
        val chapters = playbackManager.chapters.value
        logger.debug { "seekToChapter called: index=$index, chaptersSize=${chapters.size}" }
        val chapter = chapters.getOrNull(index)
        if (chapter == null) {
            logger.warn { "seekToChapter: chapter at index $index not found" }
            return
        }

        logger.debug { "seekToChapter: chapter='${chapter.title}', startTime=${chapter.startTime}" }
        playbackController.seekTo(chapter.startTime)
        // Update PlaybackManager so UI updates immediately (even when paused)
        playbackManager.updatePosition(chapter.startTime)
        hideChapterPicker()
    }

    fun seekWithinChapter(progress: Float) {
        logger.debug { "seekWithinChapter called: progress=$progress" }
        val currentIndex = playbackManager.currentChapter.value?.index ?: 0
        val chapters = playbackManager.chapters.value
        val currentChapter = chapters.getOrNull(currentIndex)
        if (currentChapter == null) {
            logger.warn { "seekWithinChapter: no current chapter" }
            return
        }

        val targetPosition = currentChapter.startTime + (currentChapter.duration * progress).toLong()
        logger.debug { "seekWithinChapter: chapter='${currentChapter.title}', targetPosition=$targetPosition" }

        playbackController.seekTo(targetPosition)
        // Update PlaybackManager so UI updates immediately (even when paused)
        playbackManager.updatePosition(targetPosition)
    }

    /**
     * Set playback speed.
     * Marks the book as having a custom speed (hasCustomSpeed=true).
     */
    fun setSpeed(speed: Float) {
        playbackController.setPlaybackSpeed(speed)
        // Notify PlaybackManager that user explicitly changed speed
        playbackManager.onSpeedChanged(speed)
    }

    /**
     * Reset speed to universal default.
     * Marks the book as using the universal default (hasCustomSpeed=false).
     */
    fun resetSpeedToDefault() {
        viewModelScope.launch {
            val defaultSpeed = playbackPreferences.getDefaultPlaybackSpeed()
            playbackController.setPlaybackSpeed(defaultSpeed)
            // Notify PlaybackManager that user reset to default
            playbackManager.onSpeedReset(defaultSpeed)
        }
    }

    /**
     * Set volume boost for the current book.
     * Marks the book as having a custom boost (hasCustomBoost=true).
     *
     * Unlike [setSpeed], there is no separate controller apply here: boost flows through
     * [PlaybackManager.effectiveGainDb] into the platform gain stage (e.g. Android's Media3
     * `GainAudioProcessor`), so notifying [PlaybackManager] is the whole operation.
     */
    fun setBoost(boostDb: Float) {
        playbackManager.onVolumeBoostChanged(boostDb)
    }

    /**
     * Reset volume boost to universal default.
     * Marks the book as using the universal default (hasCustomBoost=false).
     */
    fun resetBoostToDefault() {
        viewModelScope.launch {
            val defaultBoostDb = playbackPreferences.getDefaultVolumeBoostDb()
            playbackManager.onBoostReset(defaultBoostDb)
        }
    }

    fun cycleSpeed() {
        setSpeed(nextPlaybackSpeed(playbackManager.playbackSpeed.value))
    }

    /** Snapshot of the current book's chapters (non-reactive; for one-shot reads). */
    val chapters: List<Chapter> get() = playbackManager.chapters.value
}

/**
 * One-shot navigation events emitted by [NowPlayingViewModel].
 *
 * Consumed once at the host entry point via [NowPlayingViewModel.navActions].
 */
sealed interface NowPlayingNavAction {
    /**
     * Open the in-app document viewer for the given local file.
     *
     * @param localPath Absolute path to the cached document file on disk, as returned
     *   by [DocumentRepository.ensureLocal].
     */
    data class OpenDocumentViewer(
        val localPath: String,
    ) : NowPlayingNavAction
}
