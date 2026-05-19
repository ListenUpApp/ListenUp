@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.AudioFileResponse
import com.calypsan.listenup.client.data.remote.model.toEntity
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.StreamPrepareResult
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Everything a player needs to begin playback of a book, as an immutable value.
 *
 * Unlike [PlaybackManager.PrepareResult] this also carries the resolved
 * [chapters] list, so callers that do not hold a [PlaybackManager] — the iOS
 * native player — get chapters without issuing a second query.
 */
data class PreparedPlayback(
    val timeline: PlaybackTimeline,
    val chapters: List<Chapter>,
    val bookTitle: String,
    val bookAuthor: String,
    val seriesName: String?,
    val coverPath: String?,
    val resumePositionMs: Long,
    val resumeSpeed: Float,
)

/**
 * Stateless playback-preparation pipeline: turns a [BookId] into a
 * [PreparedPlayback] value (auth token refresh, book + audio-file load with
 * server fallback, timeline build, chapter load, resume-position resolution).
 *
 * Holds no mutable playback state. [PlaybackManagerImpl] constructs one
 * internally and delegates [PlaybackManager.prepareForPlayback] to it; the iOS
 * native player calls [prepare] directly via Koin.
 */
class PlaybackPreparer(
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
) {
    /**
     * Prepare playback for [bookId].
     *
     * @param onPrepareProgress invoked with transcode progress during the
     *   (Android/Desktop-only) transcode-aware timeline build, and with `null`
     *   when progress is cleared. iOS passes the no-op default.
     * @return a [PreparedPlayback] value, or `null` on any failure (logged).
     */
    suspend fun prepare(
        bookId: BookId,
        onPrepareProgress: (PlaybackManager.PrepareProgress?) -> Unit = {},
    ): PreparedPlayback? {
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
            if (file.duration <= 0) {
                logger.warn { "  ⚠️ WARNING: File[$index] has invalid duration: ${file.duration}" }
            }
            if (file.duration > 86_400_000) {
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
        val domainAudioFiles = audioFileEntities.map { it.toDomain() }
        val timeline =
            if (playbackApi != null && capabilityDetector != null) {
                val capabilities = capabilityDetector.getSupportedCodecs()
                logger.debug { "Client codec capabilities: $capabilities" }

                PlaybackTimeline.buildWithTranscodeSupport(
                    bookId = bookId,
                    audioFiles = domainAudioFiles,
                    baseUrl = serverUrl,
                    resolveLocalPath = { audioFileId -> downloadService.getLocalPath(audioFileId) },
                    prepareStream = { audioFileId, codec ->
                        prepareStreamForFile(
                            bookId.value,
                            audioFileId,
                            codec,
                            capabilities,
                            serverUrl,
                            onPrepareProgress,
                        )
                    },
                )
            } else {
                PlaybackTimeline.buildWithLocalPaths(
                    bookId = bookId,
                    audioFiles = domainAudioFiles,
                    baseUrl = serverUrl,
                    resolveLocalPath = { audioFileId -> downloadService.getLocalPath(audioFileId) },
                )
            }

        // Load chapters for this book
        val chapters = loadChapters(bookId)

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

        val resumeSpeed =
            if (savedPosition != null && savedPosition.hasCustomSpeed) {
                savedPosition.playbackSpeed
            } else {
                playbackPreferences.getDefaultPlaybackSpeed()
            }

        logger.debug {
            "Resume position: ${resumePositionMs}ms, speed: ${resumeSpeed}x (hasCustomSpeed=${savedPosition?.hasCustomSpeed})"
        }

        if (resumePositionMs < 0) {
            logger.warn { "⚠️ WARNING: Negative resume position: $resumePositionMs" }
        }
        if (resumePositionMs > timeline.totalDurationMs) {
            logger.warn {
                "⚠️ WARNING: Resume position $resumePositionMs exceeds book duration ${timeline.totalDurationMs}"
            }
        }

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

        // 7. Trigger background download if not fully downloaded (best-effort caching)
        if (!deviceContext.supportsDownloads) {
            logger.info { "Device does not support downloads, streaming only" }
        } else if (!timeline.isFullyDownloaded && !downloadService.wasExplicitlyDeleted(bookId)) {
            logger.info { "Book not fully downloaded, triggering background download" }
            scope.launch {
                downloadService.downloadBook(bookId)
            }
        } else if (!timeline.isFullyDownloaded) {
            logger.info { "Book was explicitly deleted, streaming only (no auto-download)" }
        }

        return PreparedPlayback(
            timeline = timeline,
            chapters = chapters,
            bookTitle = book.title,
            bookAuthor = bookAuthor,
            seriesName = seriesName,
            coverPath = coverPath,
            resumePositionMs = resumePositionMs,
            resumeSpeed = resumeSpeed,
        )
    }

    /**
     * Negotiate streaming URL for a single audio file. Calls the server's
     * prepare endpoint; if transcoding is in progress, polls until ready or
     * timeout, reporting progress through [onPrepareProgress].
     */
    private suspend fun prepareStreamForFile(
        bookId: String,
        audioFileId: String,
        codec: String,
        capabilities: List<String>,
        baseUrl: String,
        onPrepareProgress: (PlaybackManager.PrepareProgress?) -> Unit,
    ): StreamPrepareResult {
        val api = playbackApi ?: return fallbackStreamResult(bookId, audioFileId, baseUrl)

        val maxRetries = 120 // ~10 minutes at 5 second intervals
        val retryDelayMs = 5000L

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
                        onPrepareProgress(null)
                        return StreamPrepareResult(
                            streamUrl = response.streamUrl,
                            ready = true,
                            transcodeJobId = response.transcodeJobId,
                        )
                    }

                    onPrepareProgress(
                        PlaybackManager.PrepareProgress(
                            audioFileId = audioFileId,
                            progress = response.progress,
                            message = "Preparing audio... ${response.progress}%",
                        ),
                    )

                    logger.info {
                        "Transcoding in progress for $audioFileId: " +
                            "jobId=${response.transcodeJobId}, progress=${response.progress}%, " +
                            "waiting ${retryDelayMs}ms before retry..."
                    }
                    delay(retryDelayMs)
                }

                is Failure -> {
                    onPrepareProgress(null)
                    logger.warn {
                        "Failed to prepare stream for $audioFileId (attempt ${attempt + 1}), using fallback URL"
                    }
                    return fallbackStreamResult(bookId, audioFileId, baseUrl)
                }
            }
        }

        onPrepareProgress(null)
        logger.warn { "Transcode polling timeout for $audioFileId after $maxRetries attempts" }
        return fallbackStreamResult(bookId, audioFileId, baseUrl)
    }

    /** Fallback stream result when the prepare endpoint fails. */
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
     * Fetch book data from server and persist locally. Used as a fallback when
     * local book data is incomplete. Writes book entity + audio-file junction
     * rows atomically via [BookRepository.upsertWithAudioFiles].
     *
     * Internal visibility allows [PlaybackManagerFallbackFetchAtomicityTest] to
     * invoke the method directly.
     *
     * @return true if fetch + persist succeeded.
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

    /** Load chapters for a book. */
    private suspend fun loadChapters(bookId: BookId): List<Chapter> {
        val entities = chapterDao.getChaptersForBook(bookId)
        val chapters =
            entities.map { entity ->
                Chapter(
                    id = entity.id.value,
                    title = entity.title,
                    duration = entity.duration,
                    startTime = entity.startTime,
                )
            }
        logger.debug { "Loaded ${chapters.size} chapters for book ${bookId.value}" }
        return chapters
    }
}

// ========== Type Conversions ==========

/**
 * Convert an [AudioFileEntity] to the domain [AudioFile], carrying the
 * [AudioFileEntity.index] ordering field.
 */
private fun AudioFileEntity.toDomain(): AudioFile =
    AudioFile(
        id = id,
        index = index,
        filename = filename,
        format = format,
        codec = codec,
        duration = duration,
        size = size,
    )

/** Convert an [AudioFileEntity] to the API-shaped [AudioFileResponse] for diagnostic logging. */
private fun AudioFileEntity.toAudioFileResponse(): AudioFileResponse =
    AudioFileResponse(
        id = id,
        filename = filename,
        format = format,
        codec = codec,
        duration = duration,
        size = size,
    )
