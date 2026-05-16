@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.data.remote.model.AudioFileResponse
import com.calypsan.listenup.client.data.remote.model.toEntity
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.StreamPrepareResult
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.download.DownloadService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Default [PlaybackManager] implementation. See the interface KDoc on
 * [PlaybackManager] for the contract; this class is the sole production
 * realisation, wired into Koin as `single<PlaybackManager> { PlaybackManagerImpl(...) }`.
 */
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
    private val playbackApi: PlaybackApiContract?,
    private val capabilityDetector: AudioCapabilityDetector?,
    private val syncApi: SyncApiContract?,
    private val scope: CoroutineScope,
    private val bookRepository: BookRepository,
) : PlaybackManager {
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

    // Transcode preparation progress (0-100, null when not preparing)
    private val _prepareProgress = MutableStateFlow<PlaybackManager.PrepareProgress?>(null)
    override val prepareProgress: StateFlow<PlaybackManager.PrepareProgress?> = _prepareProgress

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
        logger.info { "Preparing playback for book: ${bookId.value}" }

        // 1. Ensure fresh auth token
        tokenProvider.prepareForPlayback()

        // 2. Get server URL
        val serverUrl = serverConfig.getServerUrl()?.value
        if (serverUrl == null) {
            logger.error { "No server URL configured" }
            return null
        }

        // 3. Get book with contributors from database
        val bookWithContributors = bookDao.getByIdWithContributors(bookId)
        if (bookWithContributors == null) {
            logger.error { "Book not found: ${bookId.value}" }
            return null
        }
        val book = bookWithContributors.book

        // Extract author names (use creditedAs when available for proper attribution)
        val contributorsById = bookWithContributors.contributors.associateBy { it.id }
        val authorNames =
            bookWithContributors.contributorRoles
                .filter { it.role == ContributorRole.AUTHOR.apiValue }
                .mapNotNull { crossRef ->
                    contributorsById[crossRef.contributorId]?.let { entity ->
                        crossRef.creditedAs ?: entity.name
                    }
                }.distinct()
        val bookAuthor = authorNames.joinToString(", ").ifEmpty { "Unknown Author" }

        // Get series name (first series if multiple)
        val seriesName = bookWithContributors.series.firstOrNull()?.name

        // Get cover path (if exists on disk)
        val coverPath =
            if (imageStorage.exists(bookId)) {
                imageStorage.getCoverPath(bookId)
            } else {
                null
            }

        // 4. Load audio files from the junction. Fallback-fetch if empty locally.
        var audioFileEntities = audioFileDao.getForBook(bookId.value)
        if (audioFileEntities.isEmpty()) {
            logger.info { "No audio files for book: ${bookId.value}, fetching from server..." }

            val fetched = fetchBookFromServer(bookId)
            if (!fetched) {
                logger.error { "Failed to fetch book from server: ${bookId.value}" }
                return null
            }
            audioFileEntities = audioFileDao.getForBook(bookId.value)
            if (audioFileEntities.isEmpty()) {
                logger.error { "Audio files still empty after fallback fetch for ${bookId.value}" }
                return null
            }
        }

        val audioFiles: List<AudioFileResponse> = audioFileEntities.map { it.toAudioFileResponse() }

        // Log detailed audio file info for diagnostics
        logger.debug { "=== Audio Files for book ${bookId.value} ===" }
        var totalDuration = 0L
        audioFiles.forEachIndexed { index, file ->
            logger.debug {
                "  File[$index]: id=${file.id}, filename=${file.filename}, " +
                    "duration=${file.duration}ms (${file.duration / 1000}s), " +
                    "size=${file.size}, format=${file.format}"
            }
            // Check for problematic values
            if (file.duration <= 0) {
                logger.warn { "  ⚠️ WARNING: File[$index] has invalid duration: ${file.duration}" }
            }
            if (file.duration > 86_400_000) { // More than 24 hours - suspicious
                logger.warn {
                    "  ⚠️ WARNING: File[$index] has suspiciously large duration: " +
                        "${file.duration}ms (${file.duration / 3_600_000}h)"
                }
            }
            totalDuration += file.duration
        }
        logger.debug {
            "=== Total calculated duration: ${totalDuration}ms (${totalDuration / 1000}s / ${totalDuration / 60000}min) ==="
        }

        // 5. Build PlaybackTimeline with codec negotiation (if available) or local path resolution
        val domainAudioFiles = audioFiles.map { it.toDomain() }
        val timeline =
            if (playbackApi != null && capabilityDetector != null) {
                // Use transcode-aware timeline building
                val capabilities = capabilityDetector.getSupportedCodecs()
                logger.debug { "Client codec capabilities: $capabilities" }

                PlaybackTimeline.buildWithTranscodeSupport(
                    bookId = bookId,
                    audioFiles = domainAudioFiles,
                    baseUrl = serverUrl,
                    resolveLocalPath = { audioFileId -> downloadService.getLocalPath(audioFileId) },
                    prepareStream = { audioFileId, codec ->
                        prepareStreamForFile(bookId.value, audioFileId, codec, capabilities, serverUrl)
                    },
                )
            } else {
                // Fallback to basic local path resolution
                PlaybackTimeline.buildWithLocalPaths(
                    bookId = bookId,
                    audioFiles = domainAudioFiles,
                    baseUrl = serverUrl,
                    resolveLocalPath = { audioFileId -> downloadService.getLocalPath(audioFileId) },
                )
            }
        _currentTimeline.value = timeline
        // Note: currentBookId is set by caller after reachability checks pass
        _totalDurationMs.value = timeline.totalDurationMs

        // Load chapters for this book
        loadChapters(bookId)

        logger.info { "Built timeline: ${timeline.files.size} files, ${timeline.totalDurationMs}ms total" }

        // 6. Get resume position and speed
        val savedPosition = progressTracker.getResumePosition(bookId)

        val resumePositionMs =
            if (savedPosition?.isFinished == true) {
                logger.info { "Book is finished - starting from beginning for re-read" }
                0L
            } else {
                savedPosition?.positionMs ?: 0L
            }

        // Determine playback speed: use book's custom speed if set, otherwise use universal default
        val resumeSpeed =
            if (savedPosition != null && savedPosition.hasCustomSpeed) {
                // Book has a custom speed set by user
                savedPosition.playbackSpeed
            } else {
                // Use universal default speed (synced across devices)
                playbackPreferences.getDefaultPlaybackSpeed()
            }

        logger.debug {
            "Resume position: ${resumePositionMs}ms, speed: ${resumeSpeed}x (hasCustomSpeed=${savedPosition?.hasCustomSpeed})"
        }

        // Validate resume position
        if (resumePositionMs < 0) {
            logger.warn { "⚠️ WARNING: Negative resume position: $resumePositionMs" }
        }
        if (resumePositionMs > timeline.totalDurationMs) {
            logger.warn {
                "⚠️ WARNING: Resume position $resumePositionMs exceeds book duration ${timeline.totalDurationMs}"
            }
        }

        // Test timeline.resolve() with resume position
        val resolvedPosition = timeline.resolve(resumePositionMs)
        logger.debug {
            "Resolved resume position: " +
                "mediaItemIndex=${resolvedPosition.mediaItemIndex}, " +
                "positionInFileMs=${resolvedPosition.positionInFileMs}"
        }

        if (resolvedPosition.mediaItemIndex >= timeline.files.size) {
            logger.warn {
                "⚠️ WARNING: Invalid mediaItemIndex ${resolvedPosition.mediaItemIndex} >= ${timeline.files.size}"
            }
        }

        // 7. Trigger background download if not fully downloaded
        // Skip if user explicitly deleted - they chose to stream only
        // Skip on devices that don't support downloads (TV, Auto) - stream only
        // Result ignored intentionally - this is best-effort caching, user can still stream
        if (!deviceContext.supportsDownloads) {
            logger.info { "Device does not support downloads, streaming only" }
        } else if (!timeline.isFullyDownloaded && !downloadService.wasExplicitlyDeleted(bookId)) {
            logger.info { "Book not fully downloaded, triggering background download" }
            scope.launch {
                downloadService.downloadBook(bookId) // Result logged in DownloadManager
            }
        } else if (!timeline.isFullyDownloaded) {
            logger.info { "Book was explicitly deleted, streaming only (no auto-download)" }
        }

        return PlaybackManager.PrepareResult(
            timeline = timeline,
            bookTitle = book.title,
            bookAuthor = bookAuthor,
            seriesName = seriesName,
            coverPath = coverPath,
            totalChapters = _chapters.value.size,
            resumePositionMs = resumePositionMs,
            resumeSpeed = resumeSpeed,
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
                                        com.calypsan.listenup.client.core
                                            .currentEpochMilliseconds(),
                                )
                        }

                        if (playbackState == PlaybackState.Ended) {
                            val duration = totalDurationMs.value
                            val position = currentPositionMs.value
                            // Guard: only mark finished if position is actually near the end.
                            // Prevents false completion from spurious Ended events on player
                            // release/stop (#204).
                            if (duration > 0 && position.toFloat() / duration >= 0.90f) {
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
     * Check if the server is reachable with a quick health check.
     * Used to warn users before attempting to stream non-downloaded content.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun isServerReachable(): Boolean {
        val url = serverConfig.getActiveUrl()?.value ?: return false
        return try {
            val client =
                HttpClient {
                    installListenUpErrorHandling()

                    install(HttpTimeout) {
                        requestTimeoutMillis = 3_000
                        connectTimeoutMillis = 3_000
                        socketTimeoutMillis = 3_000
                    }
                }
            try {
                val response = client.get("$url/health")
                response.status.value in 200..299
            } finally {
                client.close()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug { "Server reachability check failed: ${e.message}" }
            false
        }
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
                    com.calypsan.listenup.client.core
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
     * Negotiate streaming URL for a single audio file.
     *
     * Calls the server's prepare endpoint to get the correct URL
     * based on client capabilities. If transcoding is in progress,
     * polls until ready or timeout, emitting progress updates.
     */
    private suspend fun prepareStreamForFile(
        bookId: String,
        audioFileId: String,
        codec: String,
        capabilities: List<String>,
        baseUrl: String,
    ): StreamPrepareResult {
        val api = playbackApi ?: return fallbackStreamResult(bookId, audioFileId, baseUrl)

        val maxRetries = 120 // ~10 minutes at 5 second intervals
        val retryDelayMs = 5000L

        // Get spatial audio setting from repository
        val spatial = playbackPreferences.getSpatialPlayback()

        repeat(maxRetries) { attempt ->
            when (val result = api.preparePlayback(bookId, audioFileId, capabilities, spatial)) {
                is Success -> {
                    val response = result.data
                    logger.debug {
                        "Prepare result for $audioFileId (attempt ${attempt + 1}): " +
                            "ready=${response.ready}, variant=${response.variant}, codec=${response.codec}"
                    }

                    if (response.ready) {
                        // Clear progress when ready
                        _prepareProgress.value = null
                        return StreamPrepareResult(
                            streamUrl = response.streamUrl,
                            ready = true,
                            transcodeJobId = response.transcodeJobId,
                        )
                    }

                    // Transcoding in progress - emit progress and retry
                    _prepareProgress.value =
                        PlaybackManager.PrepareProgress(
                            audioFileId = audioFileId,
                            progress = response.progress,
                            message = "Preparing audio... ${response.progress}%",
                        )

                    logger.info {
                        "Transcoding in progress for $audioFileId: " +
                            "jobId=${response.transcodeJobId}, progress=${response.progress}%, " +
                            "waiting ${retryDelayMs}ms before retry..."
                    }
                    kotlinx.coroutines.delay(retryDelayMs)
                }

                is Failure -> {
                    _prepareProgress.value = null
                    logger.warn {
                        "Failed to prepare stream for $audioFileId (attempt ${attempt + 1}), " +
                            "using fallback URL"
                    }
                    return fallbackStreamResult(bookId, audioFileId, baseUrl)
                }
            }
        }

        // Timeout - return what we have (stream URL that may 202)
        _prepareProgress.value = null
        logger.warn { "Transcode polling timeout for $audioFileId after $maxRetries attempts" }
        return fallbackStreamResult(bookId, audioFileId, baseUrl)
    }

    /**
     * Fallback stream result when prepare endpoint fails.
     */
    private fun fallbackStreamResult(
        bookId: String,
        audioFileId: String,
        baseUrl: String,
    ): StreamPrepareResult =
        StreamPrepareResult(
            streamUrl = "$baseUrl/api/v1/books/$bookId/audio/$audioFileId",
            ready = true,
            transcodeJobId = null,
        )

    /**
     * Fetch book data from server and persist locally.
     *
     * Used as a fallback when local book data is incomplete (e.g., book synced
     * before audio files were indexed). Writes both the book entity AND its
     * audio-file junction rows in a single atomically block, so a partial
     * persistence failure doesn't leave the DB holding a book with stale or
     * missing audio files.
     *
     * Internal visibility allows [PlaybackManagerFallbackFetchAtomicityTest] to
     * invoke the method directly. Not part of the public API.
     *
     * @param bookId The book ID to fetch.
     * @return true if the fetch + persist succeeded, false otherwise. Callers
     *   re-query [audioFileDao] after a successful fetch to obtain the rows.
     */
    internal suspend fun fetchBookFromServer(bookId: BookId): Boolean {
        val api = syncApi
        if (api == null) {
            logger.error { "SyncApi not available for fetching book" }
            return false
        }

        return when (val result = api.getBook(bookId.value)) {
            is Success -> {
                val bookResponse = result.data
                logger.info { "Fetched book from server: ${bookResponse.title}" }

                val entity = bookResponse.toEntity()
                val audioFileRows =
                    bookResponse.audioFiles.mapIndexed { idx, af ->
                        AudioFileEntity(
                            bookId = bookId,
                            index = idx,
                            id = af.id,
                            filename = af.filename,
                            format = af.format,
                            codec = af.codec,
                            duration = af.duration,
                            size = af.size,
                        )
                    }

                when (val writeResult = bookRepository.upsertWithAudioFiles(entity, audioFileRows)) {
                    is AppResult.Success -> {
                        logger.debug { "Saved fetched book + ${audioFileRows.size} audio files to local database" }
                        true
                    }

                    is AppResult.Failure -> {
                        logger.error { "Failed to persist fetched book ${bookId.value}: ${writeResult.error.message}" }
                        false
                    }
                }
            }

            is Failure -> {
                logger.error { "Failed to fetch book from server: ${bookId.value}" }
                false
            }
        }
    }

    /**
     * Load chapters for a book.
     */
    private suspend fun loadChapters(bookId: BookId) {
        val entities = chapterDao.getChaptersForBook(bookId)
        _chapters.value =
            entities.map { entity ->
                Chapter(
                    id = entity.id.value,
                    title = entity.title,
                    duration = entity.duration,
                    startTime = entity.startTime,
                )
            }
        logger.debug { "Loaded ${_chapters.value.size} chapters for book ${bookId.value}" }
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

// ========== Type Conversions ==========

/**
 * Convert data layer AudioFileResponse to domain AudioFile.
 */
private fun AudioFileResponse.toDomain(): AudioFile =
    AudioFile(
        id = id,
        filename = filename,
        format = format,
        codec = codec,
        duration = duration,
        size = size,
    )

/**
 * Convert an [AudioFileEntity] to the API-shaped [AudioFileResponse] that
 * downstream playback code (timeline building, codec negotiation) consumes.
 *
 * This mapper exists because the domain `AudioFile` conversion downstream
 * still operates on `AudioFileResponse`. W6 or later can migrate the whole
 * pipeline to operate on entities or a domain type directly.
 */
private fun AudioFileEntity.toAudioFileResponse(): AudioFileResponse =
    AudioFileResponse(
        id = id,
        filename = filename,
        format = format,
        codec = codec,
        duration = duration,
        size = size,
    )
