
package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
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
 * release/stop falsely marking a book finished (#204).
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
class PlaybackManagerImpl(
    private val serverConfig: ServerConfig,
    private val playbackPreferences: PlaybackPreferences,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val chapterDao: ChapterDao,
    private val imageStorage: ImageStorage,
    private val progressTracker: ProgressTracker,
    private val tokenProvider: AudioTokenProvider,
    private val deviceContext: DeviceContext,
    private val downloadService: DownloadService,
    private val playbackRpcFactory: PlaybackRpcFactory,
    private val syncApi: SyncApiContract?,
    private val scope: CoroutineScope,
    private val bookRepository: BookRepository,
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
            playbackRpcFactory = playbackRpcFactory,
            syncApi = syncApi,
            scope = scope,
            bookRepository = bookRepository,
        )

    private val _currentBookId = MutableStateFlow<BookId?>(null)
    override val currentBookId: StateFlow<BookId?> = _currentBookId

    /** String version of currentBookId for Swift/SKIE (value classes dont bridge to flows) */
    private val _currentBookIdString = MutableStateFlow<String?>(null)
    val currentBookIdString: StateFlow<String?> = _currentBookIdString

    private val _currentTimeline = MutableStateFlow<PlaybackTimeline?>(null)
    override val currentTimeline: StateFlow<PlaybackTimeline?> = _currentTimeline

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs

    private val _totalDurationMs = MutableStateFlow(0L)
    override val totalDurationMs: StateFlow<Long> = _totalDurationMs

    private val _playbackSpeed = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed

    // Error state for displaying playback errors to the user
    // Null means no error, non-null means error to display
    private val _playbackError = MutableStateFlow<PlaybackManager.PlaybackErrorUiState?>(null)
    override val playbackError: StateFlow<PlaybackManager.PlaybackErrorUiState?> = _playbackError

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val playbackState: StateFlow<PlaybackState> = _playbackState

    // Chapter state for notification and UI
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    override val chapters: StateFlow<List<Chapter>> = _chapters

    private val _currentChapter = MutableStateFlow<PlaybackManager.ChapterInfo?>(null)
    override val currentChapter: StateFlow<PlaybackManager.ChapterInfo?> = _currentChapter

    // Tracks the coroutine that observes AudioPlayer state/position on Desktop/Apple.
    // Cancelled by clearPlayback so observations don't outlive a playback session.
    private var playerObservationJob: Job? = null

    // Callback for chapter changes - used by PlaybackService to update notification
    override var onChapterChanged: ((PlaybackManager.ChapterInfo) -> Unit)? = null

    /** Set the current book ID — call this only when playback is confirmed to proceed. */
    override fun activateBook(bookId: BookId) {
        _currentBookId.value = bookId
        _currentBookIdString.value = bookId.value
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

        _currentTimeline.value = prepared.timeline
        // Note: currentBookId is set by caller after reachability checks pass
        _totalDurationMs.value = prepared.timeline.totalDurationMs
        _chapters.value = prepared.chapters

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
        _playbackSpeed.value = resumeSpeed

        // Resume from saved position
        if (resumePositionMs > 0) {
            player.seekTo(resumePositionMs)
        }
        _currentPositionMs.value = resumePositionMs

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

                        // Drift #29 — error routing. AudioPlayer actuals emit
                        // PlaybackState.Error(message?) for platform-native failures;
                        // PlaybackManager turns that into PlaybackError on the public flow.
                        // (Android emits errors via [reportError] from MediaControllerHolder;
                        // setPlaybackState never carries Error on the Android path.)
                        // Drift #28 — Playing/Paused → progressTracker routing lives in
                        // [setPlaybackState] so both Desktop+iOS (via this collect) and
                        // Android (via PlaybackStateWriter.setPlaybackState from
                        // MediaControllerHolder.Player.Listener) flow through one path.
                        if (playbackState is PlaybackState.Error) {
                            _playbackError.value =
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
                            // release/stop (#204).
                            if (duration > 0 && position.toFloat() / duration >= BOOK_FINISHED_THRESHOLD) {
                                progressTracker.onBookFinished(bookId, duration)
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
        // emission (drift #28); no explicit call here.
        player.play()

        logger.info { "Playback started via AudioPlayer at position ${resumePositionMs}ms, speed ${resumeSpeed}x" }
    }

    /**
     * Update playing flag. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener; Desktop: PlaybackManager's
     * own AudioPlayer.state observation in startPlayback).
     */
    override fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    /**
     * Update buffering flag. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener; Desktop: PlaybackManager's
     * own AudioPlayer.state observation in startPlayback).
     */
    override fun setBuffering(buffering: Boolean) {
        _isBuffering.value = buffering
    }

    /**
     * Update playback state (Idle/Buffering/Playing/Paused/Ended/Error). Same
     * caller scheme as [setBuffering].
     *
     * Drift #28 — every Playing/Paused transition (whether triggered by Desktop's
     * AudioPlayer state observation in [playerObservationJob] or Android's
     * [MediaControllerHolder.Player.Listener] pushing through this seam) routes
     * through [progressTracker] here. VMs no longer call the tracker directly.
     * Playing also clears any previous [playbackError] so transient failures
     * resolve as soon as the player recovers.
     */
    override fun setPlaybackState(state: PlaybackState) {
        _playbackState.value = state
        when (state) {
            PlaybackState.Playing -> {
                _playbackError.value = null
                currentBookId.value?.let { activeBookId ->
                    progressTracker.onPlaybackStarted(
                        activeBookId,
                        currentPositionMs.value,
                        playbackSpeed.value,
                    )
                }
            }

            PlaybackState.Paused -> {
                currentBookId.value?.let { activeBookId ->
                    progressTracker.onPlaybackPaused(
                        activeBookId,
                        currentPositionMs.value,
                        playbackSpeed.value,
                    )
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
        _currentPositionMs.value = positionMs
        updateCurrentChapter(positionMs)
    }

    /**
     * Update playback speed. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener on PlaybackParameters
     * change; Desktop: PlaybackManager's own AudioPlayer.state observation in
     * startPlayback).
     */
    override fun updateSpeed(speed: Float) {
        _playbackSpeed.value = speed
    }

    /**
     * Called when user explicitly changes playback speed for the current book.
     *
     * Writes per-book only via [progressTracker.onSpeedChanged], which sets
     * `hasCustomSpeed = true`. The global default is changed only via
     * Settings → Default Speed; per-book changes do NOT mutate the global default.
     */
    override fun onSpeedChanged(speed: Float) {
        val bookId = currentBookId.value ?: return
        val positionMs = currentPositionMs.value
        _playbackSpeed.value = speed
        progressTracker.onSpeedChanged(bookId, positionMs, speed)
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
        _playbackSpeed.value = defaultSpeed
        progressTracker.onSpeedReset(bookId, positionMs, defaultSpeed)
    }

    /**
     * Clear current playback state.
     * Called when playback stops or when access is revoked.
     */
    override fun clearPlayback() {
        playerObservationJob?.cancel()
        playerObservationJob = null
        _currentBookId.value = null
        _currentBookIdString.value = null
        _currentTimeline.value = null
        _chapters.value = emptyList()
        _currentChapter.value = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _totalDurationMs.value = 0L
        _playbackSpeed.value = 1.0f
        _playbackError.value = null
        _isBuffering.value = false
        _playbackState.value = PlaybackState.Idle
    }

    /**
     * Report a playback error to be displayed to the user.
     * Called by platform-specific error handlers.
     */
    override fun reportError(
        message: String,
        isRecoverable: Boolean,
    ) {
        _playbackError.value =
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
        _playbackError.value = null
    }

    /**
     * Update current chapter based on position.
     * Called from updatePosition() to track chapter changes.
     */
    internal fun updateCurrentChapter(positionMs: Long) {
        val chapterList = _chapters.value
        if (chapterList.isEmpty()) {
            _currentChapter.value = null
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
        if (newChapter.index != _currentChapter.value?.index) {
            _currentChapter.value = newChapter
            onChapterChanged?.invoke(newChapter)
        } else {
            // Update remaining time without triggering notification
            _currentChapter.value = newChapter
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
