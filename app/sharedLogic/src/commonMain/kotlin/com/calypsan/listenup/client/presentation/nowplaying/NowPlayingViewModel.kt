
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
import com.calypsan.listenup.client.playback.mapToNowPlayingState
import com.calypsan.listenup.client.playback.mapToPlaybackProgress
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * Lifecycle: Bound as a Koin `single` — survives recomposition
 * and lives for the process lifetime. `init { playbackController.acquire() }` establishes
 * the MediaController connection on first resolution and holds it for the process;
 * `onCleared` never fires, so a matching `release()` would be dead code. The
 * PlaybackController object's Koin singleton lifetime does **not** establish the
 * connection — only `acquire()` does.
 *
 * Exposes ~25 distinct, intent-named user actions (transport, chapter, speed,
 * sleep-timer, sheet visibility), each mapping to a single UI affordance; merging
 * them to satisfy the function-count metric would reduce call-site clarity, hence
 * the narrow [Suppress] (replaces the former file-level suppression).
 */
@Suppress("TooManyFunctions")
class NowPlayingViewModel(
    private val playbackManager: PlaybackManager,
    private val bookRepository: BookRepository,
    private val sleepTimerManager: SleepTimerManager,
    private val playbackController: PlaybackController,
    private val playbackPreferences: PlaybackPreferences,
    private val networkMonitor: NetworkMonitor,
    private val documentRepository: DocumentRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
) : ViewModel() {
    private companion object {
        const val FADE_DURATION_MS = 3000L
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }

    init {
        playbackController.acquire()
    }

    private val overlayFlow = MutableStateFlow<NowPlayingOverlay>(NowPlayingOverlay.None)
    private val isExpandedFlow = MutableStateFlow(false)

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
        ) { isPlaying, isBuffering, speed ->
            PlaybackDynamics(isPlaying = isPlaying, isBuffering = isBuffering, playbackSpeed = speed)
        }

    /** Aggregated surface metadata (chapter info + chapter list + error + default speed). */
    private val surfaceMetadataFlow: Flow<SurfaceMetadata> =
        combine(
            playbackManager.currentChapter,
            playbackManager.chapters,
            playbackManager.playbackError,
            playbackPreferences.observeDefaultPlaybackSpeed(),
        ) { chapter, chapters, error, defaultSpeed ->
            SurfaceMetadata(
                currentChapter = chapter,
                chapters = chapters,
                error = error,
                defaultPlaybackSpeed = defaultSpeed,
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
            isExpandedFlow,
            sleepTimerManager.state,
        ) { state, overlay, isExpanded, sleepTimer ->
            NowPlayingScreenState(
                state = state,
                overlay = overlay,
                isExpanded = isExpanded,
                sleepTimerState = sleepTimer,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue =
                NowPlayingScreenState(
                    state = NowPlayingState.Idle,
                    overlay = NowPlayingOverlay.None,
                    isExpanded = false,
                    sleepTimerState = sleepTimerManager.state.value,
                ),
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
        // The try/catch ensures onFadeCompleted() (which resets state to Inactive) is
        // always called — even when fadeOutAndPause() throws or when viewModelScope is
        // cancelled mid-fade. Without this, a thrown exception leaves the timer stuck
        // in FadingOut, which causes PlaybackService to apply the short sleep-idle
        // timeout to every subsequent pause.
        viewModelScope.launch {
            sleepTimerManager.sleepEvent.collect {
                try {
                    fadeOutAndPause()
                } catch (e: CancellationException) {
                    sleepTimerManager.onFadeCompleted()
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "fadeOutAndPause failed — resetting sleep timer to Inactive" }
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

    /**
     * Gradually reduce volume to zero, then pause.
     * Called when sleep timer fires.
     */
    private suspend fun fadeOutAndPause() {
        logger.info { "Starting volume fade out" }

        val steps = 30
        val stepDelay = FADE_DURATION_MS / steps
        val volumeStep = 1f / steps

        var currentVolume = 1f

        repeat(steps) {
            currentVolume = (currentVolume - volumeStep).coerceAtLeast(0f)
            playbackController.setVolume(currentVolume)
            delay(stepDelay)
        }

        // Pause playback
        playbackController.pause()

        // Brief delay then restore volume for next play
        delay(100)
        playbackController.setVolume(1f)

        logger.info { "Fade complete, playback paused" }
        sleepTimerManager.onFadeCompleted()
    }

    // === UI ephemera setters ===

    fun expand() {
        isExpandedFlow.value = true
    }

    fun collapse() {
        isExpandedFlow.value = false
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

    fun playBook(bookId: BookId) {
        viewModelScope.launch {
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
        }
    }

    fun playPause() {
        if (playbackManager.isPlaying.value) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun skipBack(seconds: Int = 10) {
        logger.debug { "skipBack called: seconds=$seconds" }
        val timeline = playbackManager.currentTimeline.value
        if (timeline == null) {
            logger.warn { "skipBack: timeline is null" }
            return
        }

        val currentBookPos = playbackManager.currentPositionMs.value
        val speed = playbackManager.playbackSpeed.value
        val newBookPos = (currentBookPos - (seconds * speed * 1000).toLong()).coerceAtLeast(0)
        logger.debug { "skipBack: currentPos=$currentBookPos, newPos=$newBookPos" }

        playbackController.seekTo(newBookPos)
        // Update PlaybackManager so UI updates immediately (even when paused)
        playbackManager.updatePosition(newBookPos)
    }

    fun skipForward(seconds: Int = 30) {
        logger.debug { "skipForward called: seconds=$seconds" }
        val timeline = playbackManager.currentTimeline.value
        if (timeline == null) {
            logger.warn { "skipForward: timeline is null" }
            return
        }

        val currentBookPos = playbackManager.currentPositionMs.value
        val totalDuration = playbackManager.totalDurationMs.value
        val speed = playbackManager.playbackSpeed.value
        val newBookPos =
            (currentBookPos + (seconds * speed * 1000).toLong()).coerceAtMost(
                totalDuration,
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

    fun cycleSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        val currentSpeed = playbackManager.playbackSpeed.value
        val currentIndex = speeds.indexOfFirst { it >= currentSpeed - 0.01f }
        val nextIndex = if (currentIndex == -1 || currentIndex >= speeds.lastIndex) 0 else currentIndex + 1
        setSpeed(speeds[nextIndex])
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
