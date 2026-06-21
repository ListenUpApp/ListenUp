
package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.AudioFileResponse
import com.calypsan.listenup.client.data.remote.model.toEntity
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.data.repository.BookIngestPort
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** One second in milliseconds. */
private const val MS_PER_SECOND = 1000L

/** One minute in milliseconds. */
private const val MS_PER_MINUTE = 60_000L

/** One hour in milliseconds. */
private const val MS_PER_HOUR = 3_600_000L

/** 24 hours in milliseconds — ceiling above which a single-file duration is suspicious. */
private const val MAX_PLAUSIBLE_FILE_DURATION_MS = 86_400_000L

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
 *
 * LongParameterList suppressed: the playback-prep pipeline orchestrates auth,
 * persistence (3 DAOs + repo), cover storage, progress, signed-URL RPC, and
 * download across the subsystem; [PlaybackManagerImpl] forwards the same
 * collaborators. A parameter object would only bag them and ripples into platform code.
 */
@Suppress("LongParameterList")
internal class PlaybackPreparer(
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
    private val bookIngestPort: BookIngestPort,
) {
    /**
     * Prepare playback for [bookId].
     *
     * Offline-first: if every audio file is already downloaded, the server prepare
     * endpoint is skipped entirely and local paths are used. Otherwise, a single call
     * to [PlaybackService.prepare] fetches signed streaming URLs for all files.
     *
     * @return a [PreparedPlayback] value, or `null` on any failure (logged).
     */
    suspend fun prepare(bookId: BookId): PreparedPlayback? {
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

        val bookAuthor = deriveAuthorName(bookWithContributors)

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

        logAudioFileDiagnostics(bookId, audioFiles)

        // 5. Build PlaybackTimeline — offline-first via signed RPC URLs
        val domainAudioFiles = audioFileEntities.map { it.toDomain() }
        val timeline = buildTimeline(bookId, domainAudioFiles, serverUrl) ?: return null

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
     * Derive the comma-joined author display name from the book's contributor
     * roles, preferring `creditedAs` over the contributor's canonical name for
     * proper attribution. Falls back to "Unknown Author" when no author is found.
     */
    private fun deriveAuthorName(bookWithContributors: BookWithContributors): String {
        val contributorsById = bookWithContributors.contributors.associateBy { it.id }
        val authorNames =
            bookWithContributors.contributorRoles
                .filter { it.role == ContributorRole.AUTHOR.apiValue }
                .mapNotNull { crossRef ->
                    contributorsById[crossRef.contributorId]?.let { entity ->
                        crossRef.creditedAs ?: entity.name
                    }
                }.distinct()
        return authorNames.joinToString(", ").ifEmpty { "Unknown Author" }
    }

    /**
     * Log per-file diagnostics for [audioFiles], flagging invalid (≤0) and
     * suspiciously large (>24h) durations and reporting the total.
     */
    private fun logAudioFileDiagnostics(
        bookId: BookId,
        audioFiles: List<AudioFileResponse>,
    ) {
        logger.debug { "=== Audio Files for book ${bookId.value} ===" }
        var totalDuration = 0L
        audioFiles.forEachIndexed { index, file ->
            logger.debug {
                "  File[$index]: id=${file.id}, filename=${file.filename}, " +
                    "duration=${file.duration}ms (${file.duration / MS_PER_SECOND}s), " +
                    "size=${file.size}, format=${file.format}"
            }
            if (file.duration <= 0) {
                logger.warn { "  ⚠️ WARNING: File[$index] has invalid duration: ${file.duration}" }
            }
            if (file.duration > MAX_PLAUSIBLE_FILE_DURATION_MS) {
                logger.warn {
                    "  ⚠️ WARNING: File[$index] has suspiciously large duration: " +
                        "${file.duration}ms (${file.duration / MS_PER_HOUR}h)"
                }
            }
            totalDuration += file.duration
        }
        logger.debug {
            "=== Total calculated duration: ${totalDuration}ms " +
                "(${totalDuration / MS_PER_SECOND}s / ${totalDuration / MS_PER_MINUTE}min) ==="
        }
    }

    /**
     * Build the [PlaybackTimeline]: resolve each file's local path, fetch signed
     * streaming URLs from the server only when not fully downloaded (offline-first),
     * then assemble the timeline. Returns `null` if the server prepare call fails.
     */
    private suspend fun buildTimeline(
        bookId: BookId,
        domainAudioFiles: List<AudioFile>,
        serverUrl: String,
    ): PlaybackTimeline? {
        val localPaths: Map<String, String?> =
            domainAudioFiles.associate { it.id to downloadService.getLocalPath(it.id) }

        val signedUrls: Map<String, String> =
            if (localPaths.values.all { it != null }) {
                emptyMap() // fully downloaded — never touch the server (offline-first)
            } else {
                when (val result = playbackRpcFactory.playbackService().prepare(bookId)) {
                    is AppResult.Success -> {
                        result.data.audioFiles.associate { it.fileId to serverUrl + it.url }
                    }

                    is AppResult.Failure -> {
                        logger.error { "prepare() failed for ${bookId.value}: ${result.error.message}" }
                        return null
                    }
                }
            }

        return PlaybackTimeline.build(
            bookId = bookId,
            files =
                domainAudioFiles.map { file ->
                    TimelineFileInput(
                        audioFileId = file.id,
                        filename = file.filename,
                        format = file.format,
                        durationMs = file.duration,
                        size = file.size,
                        localPath = localPaths[file.id],
                        streamingUrl = signedUrls[file.id] ?: "", // "" when downloaded — localPath wins in playbackUri
                    )
                },
        )
    }

    /**
     * Fetch book data from server and persist locally. Used as a fallback when
     * local book data is incomplete. Writes book entity + audio-file junction
     * rows atomically via [BookIngestPort.upsertWithAudioFiles].
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
            is AppResult.Success -> {
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

                when (val writeResult = bookIngestPort.upsertWithAudioFiles(entity, audioFileRows)) {
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

            is AppResult.Failure -> {
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
