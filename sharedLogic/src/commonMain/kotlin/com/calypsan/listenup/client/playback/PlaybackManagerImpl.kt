
package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.download.DownloadService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Fraction of total duration a position must reach for an `Ended` state to count
 * as genuine completion. Guards against spurious `Ended` events on player
 * release/stop falsely marking a book finished.
 */
private const val BOOK_FINISHED_THRESHOLD = 0.90f

/**
 * Default [PlaybackManager] implementation. See the interface KDoc on
 * [PlaybackManager] for the contract; this class is the sole production
 * realisation, wired into Koin as `single<PlaybackManager> { PlaybackManagerImpl(...) }`.
 *
 * LongParameterList suppressed: forwards the same heterogeneous playback-prep
 * collaborators to [PlaybackPreparer] (auth, 3 DAOs + repo, cover storage, progress,
 * codec negotiation, download). A parameter object would only bag them and ripples
 * into platform code that also constructs this class.
 */
@Suppress("LongParameterList")
internal class PlaybackManagerImpl(
    private val serverConfig: ServerConfig,
    private val playbackPreferences: PlaybackPreferences,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val chapterDao: ChapterDao,
    private val imageStorage: ImageStorage,
    private val progressTracker: ProgressTracker,
    private val reporter: PlaybackProgressReporter,
    private val tokenProvider: AudioTokenProvider,
    private val deviceContext: DeviceContext,
    private val downloadService: DownloadService,
    private val prepareRepository: PlaybackPrepareRepository,
    private val channel: RpcChannel<BookService>,
    private val scope: CoroutineScope,
    private val bookSyncDomainHandler: SyncDomainHandler<BookSyncPayload>,
    private val playbackBandwidthCoordinator: PlaybackBandwidthCoordinator,
    /**
     * When true, [setPlaybackState] routes Playing/Paused transitions through
     * [reporter] to persist position and record listening spans. This is the sole
     * persistence path on Desktop/iOS (driven by [playerObservationJob]).
     *
     * Android sets this false: its Media3 `PlaybackService.PlayerListener` already
     * owns book-relative transition persistence AND listening-event recording, and
     * those same signals also reach this class via `MediaControllerHolder`. Letting
     * both persist would double-write the outbox on every play/pause. Android keeps
     * this class as the UI/StateFlow source of truth only for transitions; it remains
     * the persistence path for explicit speed changes ([onSpeedChanged]/[onSpeedReset]).
     */
    private val persistTransitionsViaReporter: Boolean = true,
) : PlaybackManager {
    private val preparer =
        PlaybackPreparer(
            serverConfig = serverConfig,
            playbackPreferences = playbackPreferences,
            bookDao = bookDao,
            audioFileDao = audioFileDao,
            chapterDao = chapterDao,
            imageStorage = imageStorage,
            progressTracker = progressTracker,
            tokenProvider = tokenProvider,
            deviceContext = deviceContext,
            downloadService = downloadService,
            prepareRepository = prepareRepository,
            channel = channel,
            scope = scope,
            bookSyncDomainHandler = bookSyncDomainHandler,
        )

    override val currentBookId: StateFlow<BookId?>
        field = MutableStateFlow<BookId?>(null)

    /** String version of currentBookId for Swift/SKIE (value classes dont bridge to flows) */
    val currentBookIdString: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    override val currentTimeline: StateFlow<PlaybackTimeline?>
        field = MutableStateFlow<PlaybackTimeline?>(null)

    override val isPlaying: StateFlow<Boolean>
        field = MutableStateFlow(false)

    override val currentPositionMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    override val totalDurationMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    override val playbackSpeed: StateFlow<Float>
        field = MutableStateFlow(1.0f)

    // Error state for displaying playback errors to the user
    // Null means no error, non-null means error to display
    override val playbackError: StateFlow<PlaybackManager.PlaybackErrorUiState?>
        field = MutableStateFlow<PlaybackManager.PlaybackErrorUiState?>(null)

    override val isBuffering: StateFlow<Boolean>
        field = MutableStateFlow(false)

    override val playbackState: StateFlow<PlaybackState>
        field = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    // Chapter state for notification and UI
    override val chapters: StateFlow<List<Chapter>>
        field = MutableStateFlow<List<Chapter>>(emptyList())

    override val currentChapter: StateFlow<PlaybackManager.ChapterInfo?>
        field = MutableStateFlow<PlaybackManager.ChapterInfo?>(null)

    // Tracks the coroutine that observes AudioPlayer state/position on Desktop/Apple.
    // Cancelled by clearPlayback so observations don't outlive a playback session.
    private var playerObservationJob: Job? = null

    // Callback for chapter changes - used by PlaybackService to update notification
    override var onChapterChanged: ((PlaybackManager.ChapterInfo) -> Unit)? = null

    /** Set the current book ID — call this only when playback is confirmed to proceed. */
    override fun activateBook(bookId: BookId) {
        currentBookId.value = bookId
        currentBookIdString.value = bookId.value
    }

    /**
     * Prepare for playback of a book.
     *
     * Steps:
     * 1. Ensure fresh auth token
     * 2. Get book from database
     * 3. Parse audio files from JSON
     * 4. Build PlaybackTimeline
     * 5. Get resume position
     *
     * @return PrepareResult with timeline and resume position, or null on failure
     */
    override suspend fun prepareForPlayback(bookId: BookId): PlaybackManager.PrepareResult? {
        val prepared = preparer.prepare(bookId) ?: return null

        currentTimeline.value = prepared.timeline
        // Note: currentBookId is set by caller after reachability checks pass
        totalDurationMs.value = prepared.timeline.totalDurationMs
        chapters.value = prepared.chapters

        return PlaybackManager.PrepareResult(
            timeline = prepared.timeline,
            bookTitle = prepared.bookTitle,
            bookAuthor = prepared.bookAuthor,
            seriesName = prepared.seriesName,
            coverPath = prepared.coverPath,
            totalChapters = prepared.chapters.size,
            resumePositionMs = prepared.resumePositionMs,
            resumeSpeed = prepared.resumeSpeed,
        )
    }

    /**
     * Start playback using a platform AudioPlayer.
     *
     * Bridges the prepared timeline to the AudioPlayer and connects
     * state flows back to PlaybackManager for position tracking.
     *
     * @param player The platform-specific audio player implementation
     * @param resumePositionMs Position to resume from (0 to start from beginning)
     * @param resumeSpeed Playback speed to use
     */
    override suspend fun startPlayback(
        player: AudioPlayer,
        resumePositionMs: Long,
        resumeSpeed: Float,
    ) {
        val timeline = currentTimeline.value
        if (timeline == null) {
            logger.error { "Cannot start playback: no timeline prepared" }
            return
        }

        val bookId = currentBookId.value
        if (bookId == null) {
            logger.error { "Cannot start playback: no book ID" }
            return
        }

        // Build segments from timeline
        val segments =
            timeline.files.map { file ->
                AudioSegment(
                    url = file.streamingUrl,
                    localPath = file.localPath,
                    durationMs = file.durationMs,
                    offsetMs = file.startOffsetMs,
                )
            }

        // Load segments into player
        player.load(segments)

        // Set speed before seeking/playing
        player.setSpeed(resumeSpeed)
        playbackSpeed.value = resumeSpeed

        // Resume from saved position
        if (resumePositionMs > 0) {
            player.seekTo(resumePositionMs)
        }
        currentPositionMs.value = resumePositionMs

        // Bridge player state and position back to PlaybackManager.
        // Both child launches are parented to playerObservationJob so a single
        // cancel() in clearPlayback stops both collectors together.
        playerObservationJob?.cancel()
        playerObservationJob =
            scope.launch {
                launch {
                    player.positionMs.collect { position ->
                        updatePosition(position)
                    }
                }
                launch {
                    player.state.collect { playbackState ->
                        setPlaybackState(playbackState)
                        setBuffering(playbackState == PlaybackState.Buffering)

                        val playing = playbackState == PlaybackState.Playing
                        setPlaying(playing)

                        // Error routing: AudioPlayer actuals emit
                        // PlaybackState.Error(message?) for platform-native failures;
                        // PlaybackManager turns that into PlaybackError on the public flow.
                        // (Android emits errors via [reportError] from MediaControllerHolder;
                        // setPlaybackState never carries Error on the Android path.)
                        // Playing/Paused → [reporter] (position + listening-event)
                        // routing lives in [setPlaybackState] so both Desktop (via this collect)
                        // and Android (via PlaybackStateWriter.setPlaybackState from
                        // MediaControllerHolder.Player.Listener) flow through one path.
                        if (playbackState is PlaybackState.Error) {
                            playbackError.value =
                                PlaybackManager.PlaybackErrorUiState(
                                    message = playbackState.message ?: "Playback failed.",
                                    isRecoverable = playbackState.isRecoverable,
                                    timestampMs =
                                        com.calypsan.listenup.core
                                            .currentEpochMilliseconds(),
                                )
                        }

                        if (playbackState == PlaybackState.Ended) {
                            val duration = totalDurationMs.value
                            val position = currentPositionMs.value
                            // Guard: only mark finished if position is actually near the end.
                            // Prevents false completion from spurious Ended events on player
                            // release/stop.
                            if (duration > 0 && position.toFloat() / duration >= BOOK_FINISHED_THRESHOLD) {
                                reporter.onBookFinished(bookId, duration)
                            } else {
                                logger.warn {
                                    "Ignoring Ended state: position=${position}ms " +
                                        "not near end (duration=${duration}ms)"
                                }
                            }
                        }
                    }
                }
            }

        // Start playback. The Playing transition is routed through progressTracker
        // by [setPlaybackState] when the collect above forwards the player's
        // emission; no explicit call here.
        player.play()

        logger.info { "Playback started via AudioPlayer at position ${resumePositionMs}ms, speed ${resumeSpeed}x" }
    }

    /**
     * Update playing flag. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener; Desktop: PlaybackManager's
     * own AudioPlayer.state observation in startPlayback).
     */
    override fun setPlaying(playing: Boolean) {
        isPlaying.value = playing
    }

    /**
     * Update buffering flag. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener; Desktop: PlaybackManager's
     * own AudioPlayer.state observation in startPlayback).
     */
    override fun setBuffering(buffering: Boolean) {
        isBuffering.value = buffering
        // Feed the "playback preempts downloads" signal: yield bandwidth only when a
        // NOT-fully-downloaded book is buffering — a local book needs no help, a stream does.
        val streaming = currentTimeline.value?.isFullyDownloaded != true
        playbackBandwidthCoordinator.setStreamingBuffering(buffering && streaming)
    }

    /**
     * Update playback state (Idle/Buffering/Playing/Paused/Ended/Error). Same
     * caller scheme as [setBuffering].
     *
     * Every Playing/Paused transition (whether triggered by Desktop's
     * AudioPlayer state observation in [playerObservationJob] or Android's
     * [MediaControllerHolder.Player.Listener] pushing through this seam) routes
     * through [reporter] here, which persists position and (on Desktop/macOS, where a
     * recorder is bound) records the listening span. VMs no longer call it directly.
     * Playing also clears any previous [playbackError] so transient failures
     * resolve as soon as the player recovers.
     */
    override fun setPlaybackState(state: PlaybackState) {
        playbackState.value = state
        when (state) {
            PlaybackState.Playing -> {
                playbackError.value = null
                if (persistTransitionsViaReporter) {
                    currentBookId.value?.let { activeBookId ->
                        reporter.onPlaybackStarted(
                            activeBookId,
                            currentPositionMs.value,
                            playbackSpeed.value,
                        )
                    }
                }
            }

            PlaybackState.Paused -> {
                if (persistTransitionsViaReporter) {
                    currentBookId.value?.let { activeBookId ->
                        reporter.onPlaybackPaused(
                            activeBookId,
                            currentPositionMs.value,
                            playbackSpeed.value,
                        )
                    }
                }
            }

            else -> {}
        }
    }

    /**
     * Update current position. Called by platform-specific event sources
     * (Android: MediaControllerHolder's position polling loop; Desktop:
     * PlaybackManager's own AudioPlayer.state observation in startPlayback).
     */
    override fun updatePosition(positionMs: Long) {
        currentPositionMs.value = positionMs
        updateCurrentChapter(positionMs)
    }

    /**
     * Convert ExoPlayer per-file coordinates to a book-relative position via the active
     * [currentTimeline], then delegate to [updatePosition]. See [PlaybackStateWriter] for
     * why the raw file offset must never be stored directly. Falls back to the raw
     * [positionInItemMs] when no timeline is active (single-item/degenerate case).
     */
    override fun updatePositionFromMediaItem(
        mediaItemIndex: Int,
        positionInItemMs: Long,
    ) {
        val bookPositionMs =
            currentTimeline.value?.toBookPosition(mediaItemIndex, positionInItemMs)
                ?: positionInItemMs
        updatePosition(bookPositionMs)
    }

    /**
     * Update playback speed. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener on PlaybackParameters
     * change; Desktop: PlaybackManager's own AudioPlayer.state observation in
     * startPlayback).
     */
    override fun updateSpeed(speed: Float) {
        playbackSpeed.value = speed
    }

    /**
     * Called when user explicitly changes playback speed for the current book.
     *
     * Writes per-book only via [reporter] → [progressTracker.onSpeedChanged], which sets
     * `hasCustomSpeed = true`. The global default is changed only via
     * Settings → Default Speed; per-book changes do NOT mutate the global default.
     */
    override fun onSpeedChanged(speed: Float) {
        val bookId = currentBookId.value ?: return
        val positionMs = currentPositionMs.value
        playbackSpeed.value = speed
        reporter.onSpeedChanged(bookId, positionMs, speed)
    }

    /**
     * Reset book's speed to universal default.
     * Called when user explicitly resets to default speed.
     *
     * @param defaultSpeed The universal default speed from settings
     */
    override fun onSpeedReset(defaultSpeed: Float) {
        val bookId = currentBookId.value ?: return
        val positionMs = currentPositionMs.value
        playbackSpeed.value = defaultSpeed
        reporter.onSpeedReset(bookId, positionMs, defaultSpeed)
    }

    /**
     * Clear current playback state.
     * Called when playback stops or when access is revoked.
     */
    override fun clearPlayback() {
        playerObservationJob?.cancel()
        playerObservationJob = null
        currentBookId.value = null
        currentBookIdString.value = null
        currentTimeline.value = null
        chapters.value = emptyList()
        currentChapter.value = null
        isPlaying.value = false
        currentPositionMs.value = 0L
        totalDurationMs.value = 0L
        playbackSpeed.value = 1.0f
        playbackError.value = null
        isBuffering.value = false
        // Release the download-yield signal on teardown too — this path clears `isBuffering`
        // directly (not via `setBuffering`), so tell the coordinator explicitly or a clear while
        // buffering could leave downloads yielded until some later state change.
        playbackBandwidthCoordinator.setStreamingBuffering(false)
        playbackState.value = PlaybackState.Idle
    }

    /**
     * Report a playback error to be displayed to the user.
     * Called by platform-specific error handlers.
     */
    override fun reportError(
        message: String,
        isRecoverable: Boolean,
    ) {
        playbackError.value =
            PlaybackManager.PlaybackErrorUiState(
                message = message,
                isRecoverable = isRecoverable,
                timestampMs =
                    com.calypsan.listenup.core
                        .currentEpochMilliseconds(),
            )
    }

    /**
     * Clear the current playback error.
     * Called when user dismisses the error or error condition is resolved.
     */
    fun clearError() {
        playbackError.value = null
    }

    /**
     * Update current chapter based on position.
     * Called from updatePosition() to track chapter changes.
     */
    internal fun updateCurrentChapter(positionMs: Long) {
        val chapterList = chapters.value
        if (chapterList.isEmpty()) {
            currentChapter.value = null
            return
        }

        val index =
            chapterList
                .indexOfLast { it.startTime <= positionMs }
                .coerceAtLeast(0)

        val chapter = chapterList[index]
        val endMs =
            chapterList.getOrNull(index + 1)?.startTime
                ?: currentTimeline.value?.totalDurationMs
                ?: chapter.startTime

        val newChapter =
            PlaybackManager.ChapterInfo(
                index = index,
                title = chapter.title,
                startMs = chapter.startTime,
                endMs = endMs,
                remainingMs = (endMs - positionMs).coerceAtLeast(0),
                totalChapters = chapterList.size,
                isGenericTitle = isGenericChapterTitle(chapter.title),
            )

        // Only trigger notification update on chapter change
        if (newChapter.index != currentChapter.value?.index) {
            currentChapter.value = newChapter
            onChapterChanged?.invoke(newChapter)
        } else {
            // Update remaining time without triggering notification
            currentChapter.value = newChapter
        }
    }

    /**
     * Detect if a chapter title is generic (e.g., "Chapter 14", "Track 7", or empty).
     */
    private fun isGenericChapterTitle(title: String): Boolean {
        val normalized = title.trim().lowercase()
        return normalized.isEmpty() ||
            normalized.matches(Regex("""^(chapter|part|track|section)\s*\d+$""")) ||
            normalized.matches(Regex("""^\d+$"""))
    }
}
