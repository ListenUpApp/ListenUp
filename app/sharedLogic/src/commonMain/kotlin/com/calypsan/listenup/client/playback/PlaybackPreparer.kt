
package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.toAudioFile
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** One second in milliseconds. */
private const val MS_PER_SECOND = 1000L

/**
 * Latency budget for [PlaybackPreparer.fetchBookFromServer] — see its KDoc.
 *
 * Unlike [PlaybackPrepareRepository.getPosition] this call has no degrade-to-local path: it IS how
 * a book's row set lands in Room the first time, so a fetch that simply failed cannot fall back to
 * anything and playback preparation fails outright. Cutting it as tight as a nice-to-have reconcile
 * (800ms) would fail legitimate slow-but-working fetches outright, so this is a full order of
 * magnitude looser — long enough for a real, moderately-degraded fetch to still succeed. It is still
 * a fraction of [com.calypsan.listenup.client.data.remote.DEFAULT_RPC_TIMEOUT] (15s, up to ~30s once
 * doubled by a pre-delivery retry): the failure mode this closes is a dead-socket caller sitting on
 * the tap-to-audio path for tens of seconds before the outer `prepare()` catch-all folds it to null
 * and the listener sees "couldn't start playback" — the same outcome, arrived at far faster.
 */
private val FETCH_BOOK_TIMEOUT = 5.seconds

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
    val bookNarrator: String,
    val seriesName: String?,
    val coverPath: String?,
    val resumePositionMs: Long,
    val resumeSpeed: Float,
    /** Boost to start playback with: the book's custom boost, else the global default. */
    val resumeBoostDb: Float,
    /** Client-measured R128 gain for this book (synced), null until measured. */
    val measuredGainDb: Float?,
    /** Server tag-read normalization gain (ReplayGain/iTunNORM), null when the file has no tag. */
    val normalizationGainDb: Float?,
    // Navigation targets for the player's overflow menu ("Go to Series / Author / Narrator"). New
    // fields default so existing constructors (playback-prep + test fixtures) stay source-compatible.
    val seriesId: String? = null,
    val authors: List<PlaybackNavRef> = emptyList(),
    val narrators: List<PlaybackNavRef> = emptyList(),
    // Content hash of the current cover, so the player surfaces (mini player, now-playing, full
    // screen) content-address the cover and refresh it after a re-scrape instead of serving the
    // stale id-stable local file. Defaults for source-compat with existing constructors/fixtures.
    val coverHash: String? = null,
)

/**
 * A navigable reference the native player's overflow menu turns into a "Go to …" action: the
 * series or an individual author/narrator behind the currently-playing book.
 *
 * [name] is the credited display name (so it matches what the player shows); [id] is the
 * series/contributor identifier the menu navigates to.
 */
data class PlaybackNavRef(
    val id: String,
    val name: String,
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
class PlaybackPreparer internal constructor(
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
    private val prepareRepository: PlaybackPrepareRepository,
    private val channel: RpcChannel<BookService>,
    private val scope: CoroutineScope,
    private val bookSyncDomainHandler: SyncDomainHandler<BookSyncPayload>,
    private val localPreferences: LocalPreferences,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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
    suspend fun prepare(bookId: BookId): PreparedPlayback? =
        try {
            prepareInternal(bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Contract (see KDoc): "null on any failure (logged)". An escaping throw — e.g. a
            // transport exception from the streaming-prepare RPC when the book isn't downloaded
            // (buildTimeline's `playbackService().prepare` is the one unguarded RPC) — otherwise
            // crosses the Swift Export seam as an opaque `KotlinError`, and the player shows
            // "Couldn't start playback" with no cause. Fold to null and log the real exception.
            logger.error(e) { "Playback prepare failed for ${bookId.value}" }
            null
        }

    private suspend fun prepareInternal(bookId: BookId): PreparedPlayback? {
        logger.info { "Preparing playback for book: ${bookId.value}" }

        // 1. Get server URL — the ACTIVE url, so audio streams from the remote host after a roam
        //    off-LAN switches the active url away from the (now-unreachable) local one.
        val serverUrl = serverConfig.getActiveUrl()?.value
        if (serverUrl == null) {
            logger.error { "No server URL configured" }
            return null
        }

        // 2. Get book with contributors from database
        val bookWithContributors = bookDao.getByIdWithContributors(bookId)
        if (bookWithContributors == null) {
            logger.error { "Book not found: ${bookId.value}" }
            return null
        }
        val book = bookWithContributors.book

        val bookAuthor = deriveAuthorName(bookWithContributors)
        val bookNarrator = deriveNarratorName(bookWithContributors)

        // Get series name + id (first series if multiple) — id feeds the player's "Go to Series".
        val firstSeries = bookWithContributors.series.firstOrNull()
        val seriesName = firstSeries?.name
        val seriesId = firstSeries?.id?.value
        val authorRefs =
            contributorRefs(bookWithContributors.contributors, bookWithContributors.contributorRoles, ContributorRole.AUTHOR)
        val narratorRefs =
            contributorRefs(bookWithContributors.contributors, bookWithContributors.contributorRoles, ContributorRole.NARRATOR)

        // Get cover path (if exists on disk)
        val coverPath =
            if (imageStorage.exists(bookId)) {
                imageStorage.getCoverPath(bookId)
            } else {
                null
            }

        // 3. Load audio files from the junction. Fallback-fetch if empty locally.
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

        // 4. Build PlaybackTimeline — offline-first via signed RPC URLs
        val domainAudioFiles = audioFileEntities.map { it.toAudioFile() }
        val buildResult = buildTimeline(bookId, domainAudioFiles, serverUrl) ?: return null
        val timeline = buildResult.timeline

        // 5. Ensure a fresh audio token — only when playback will hit the network. A
        //    fully-downloaded book plays from local file:// paths and never needs one; awaiting a
        //    refresh unconditionally (the prior step 1) was the 8.08s stall reproduced on
        //    2026-08-13. Runs AFTER buildTimeline (isFullyDownloaded is only known once the
        //    timeline is assembled); buildTimeline's own network call rides the RPC channel's own
        //    auth, not this token provider, so nothing is lost by asking last.
        if (!timeline.isFullyDownloaded) tokenProvider.prepareForPlayback()

        // Load chapters for this book
        val chapters = loadChapters(bookId)

        logger.info { "Built timeline: ${timeline.files.size} files, ${timeline.totalDurationMs}ms total" }

        // 6. Resolve resume position and speed.
        // Position is sacred: reconcile the local Room row against the server-authoritative
        // position carried by prepare() (null on the fully-downloaded path). Whichever has the
        // greater lastPlayedAt wins — the same conflict key the playback-positions sync domain
        // uses. This closes the crown-jewel race where a device opens a book before its catch-up
        // drains, resumes from a stale local row, then stamps that stale position lastPlayedAt=now
        // and permanently clobbers another device's newer progress.
        val savedPosition = progressTracker.getResumePosition(bookId)
        // On the fully-downloaded path prepare() is skipped, so buildResult carries no server
        // position. Fetch the authoritative position directly (best-effort, bounded) so downloaded
        // books get the same newer-wins reconcile streaming books already get — closing the F1
        // clobber for the offline case. Any failure (offline / RPC) degrades to the local row.
        val serverPosition =
            buildResult.serverResumePosition
                ?: if (timeline.isFullyDownloaded) fetchAuthoritativePosition(bookId) else null
        val resolved = resolveResumePosition(savedPosition, serverPosition)

        val resumePositionMs = resumeStartPositionMs(resolved)

        val resumeSpeed =
            if (savedPosition != null && savedPosition.hasCustomSpeed) {
                savedPosition.playbackSpeed
            } else {
                playbackPreferences.getDefaultPlaybackSpeed()
            }

        val resumeBoostDb =
            if (savedPosition != null && savedPosition.hasCustomBoost) {
                savedPosition.volumeBoostDb
            } else {
                playbackPreferences.getDefaultVolumeBoostDb()
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
        triggerBackgroundDownloadIfNeeded(bookId, timeline)

        return PreparedPlayback(
            timeline = timeline,
            chapters = chapters,
            bookTitle = book.title,
            bookAuthor = bookAuthor,
            bookNarrator = bookNarrator,
            seriesName = seriesName,
            seriesId = seriesId,
            authors = authorRefs,
            narrators = narratorRefs,
            coverPath = coverPath,
            coverHash = book.coverHash,
            resumePositionMs = resumePositionMs,
            resumeSpeed = resumeSpeed,
            resumeBoostDb = resumeBoostDb,
            measuredGainDb = savedPosition?.measuredGainDb,
            normalizationGainDb = book.normalizationGainDb,
        )
    }

    /**
     * Derive the comma-joined author display name from the book's contributor
     * roles. Falls back to "Unknown Author" when no author is found.
     */
    private fun deriveAuthorName(bookWithContributors: BookWithContributors): String =
        joinContributorNames(
            bookWithContributors.contributors,
            bookWithContributors.contributorRoles,
            ContributorRole.AUTHOR,
        ).ifEmpty { "Unknown Author" }

    /**
     * Derive the comma-joined narrator display name, or an empty string when the
     * book has no narrator — the player hides the "Narrated by" line in that case.
     */
    private fun deriveNarratorName(bookWithContributors: BookWithContributors): String =
        joinContributorNames(
            bookWithContributors.contributors,
            bookWithContributors.contributorRoles,
            ContributorRole.NARRATOR,
        )

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
     * Fetch the server-authoritative resume position for the fully-downloaded path (where
     * [buildTimeline] skips `prepare()` and never sees it).
     *
     * **This DOES block — it is awaited, on the path between tapping play and hearing audio.** An
     * earlier version of this KDoc claimed it was "non-blocking"; it never was, and that sentence
     * is why a 30-second stall hid here in plain sight (15s bound + 15s idempotent retry on a
     * half-open socket). The honest statement is that it is bounded SHORT — see
     * [PlaybackPrepareRepository.getPosition] — so the cost is sub-second even when the socket is
     * dead, and any failure returns null so resume resolves from the local Room row.
     *
     * If you are tempted to widen that bound, note what it buys: only a resume point that is
     * server-fresher than Room. Losing it starts the listener a little behind; waiting for it
     * starts them in silence.
     */
    private suspend fun fetchAuthoritativePosition(bookId: BookId): PlaybackPositionSyncPayload? =
        when (val result = prepareRepository.getPosition(bookId)) {
            is AppResult.Success -> {
                result.data
            }

            is AppResult.Failure -> {
                logger.debug {
                    "getPosition failed for downloaded book ${bookId.value} (${result.error.code}); " +
                        "resuming from the local Room row"
                }
                null
            }
        }

    /**
     * The position playback should START at, given the reconciled [resolved] row.
     *
     * A finished book restarts at zero for a re-read. Otherwise the graduated [autoRewindMs] ladder
     * backs the start up by however long the listener has been away, so returning to a book does
     * not drop them mid-sentence.
     *
     * The rewind is a START OFFSET and never a write. The resolved row keeps the position the
     * listener actually reached; only the value handed to the player moves. Persisting the rewound
     * value would let repeated open/close cycles walk someone backwards through content they never
     * un-listened to.
     */
    private fun resumeStartPositionMs(resolved: ResolvedResumePosition?): Long {
        if (resolved == null) return 0L
        if (resolved.isFinished) {
            logger.info { "Book is finished - starting from beginning for re-read" }
            return 0L
        }
        val rewindMs =
            if (localPreferences.autoRewindEnabled.value) {
                autoRewindMs(nowMillis() - resolved.lastPlayedAtMs)
            } else {
                0L
            }
        if (rewindMs > 0) {
            logger.debug { "Auto-rewind: backing up ${rewindMs}ms from ${resolved.positionMs}" }
        }
        return (resolved.positionMs - rewindMs).coerceAtLeast(0L)
    }

    /**
     * Build the [PlaybackTimeline]: resolve each file's local path, fetch signed
     * streaming URLs from the server only when not fully downloaded (offline-first),
     * then assemble the timeline. Returns `null` if the server prepare call fails.
     *
     * The result also carries [TimelineBuildResult.serverResumePosition] — the
     * server-authoritative resume point from the same prepare() call — so the caller
     * can reconcile it against the local Room row instead of discarding it.
     */
    private suspend fun buildTimeline(
        bookId: BookId,
        domainAudioFiles: List<AudioFile>,
        serverUrl: String,
    ): TimelineBuildResult? {
        val localPaths: Map<String, String?> =
            domainAudioFiles.associate { it.id to downloadService.getLocalPath(it.id) }

        // Server-authoritative resume position, populated only when prepare() is called
        // (streaming path). Null on the fully-downloaded path — resume then resolves from Room alone.
        var serverResumePosition: PlaybackPositionSyncPayload? = null

        val downloadedCount = localPaths.values.count { it != null }
        val missingCount = localPaths.size - downloadedCount

        val signedUrls: Map<String, String> =
            if (missingCount == 0) {
                emptyMap() // fully downloaded — never touch the server (offline-first)
            } else {
                // Some files are missing → we need signed streaming URLs for them. prepare() returns
                // per-file URLs for the whole book; localPath still wins in playbackUri, so downloaded
                // files play from disk and only the missing ones stream.
                //
                // Routed through the bounded, self-healing engine so a dead-socket transport throw
                // becomes a typed, time-bounded AppResult.Failure instead of an unguarded exception
                // crossing the Swift Export seam as an opaque KotlinError.
                when (val result = prepareRepository.prepare(bookId)) {
                    is AppResult.Success -> {
                        serverResumePosition = result.data.resumePosition
                        result.data.audioFiles.associate { it.fileId to serverUrl + it.url }
                    }

                    is AppResult.Failure -> {
                        // prepare() failed — almost always offline. Never-stranded: if SOME files are
                        // downloaded, build the timeline from the local files so the downloaded portion
                        // plays offline instead of failing the whole book. The missing files get no URL
                        // (honest gap — playbackUri "" — not a fake streaming URL that would fail anyway).
                        if (downloadedCount == 0) {
                            logger.error {
                                "prepare() failed for ${bookId.value} and no files are downloaded: ${result.error.message}"
                            }
                            return null
                        }
                        logger.warn {
                            "prepare() failed for ${bookId.value} (${result.error.code}); playing $downloadedCount " +
                                "downloaded file(s) offline, $missingCount unavailable until reconnect."
                        }
                        emptyMap()
                    }
                }
            }

        val timeline =
            PlaybackTimeline.build(
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
        return TimelineBuildResult(timeline = timeline, serverResumePosition = serverResumePosition)
    }

    /**
     * Fetch book data from server and persist locally. Used as a fallback when
     * local book data is incomplete. Fetches the contract [BookSyncPayload] over the
     * [BookService] RPC proxy and writes the whole aggregate through the shared
     * [bookSyncDomainHandler] — the exact same atomic path the RPC on-demand fetch
     * ([com.calypsan.listenup.client.data.repository.BookRepositoryImpl] `fetchAndCacheBook`)
     * uses, so the book + audio-file rows (incl. audio-stream fields) land identically with
     * no parallel-mapping drift.
     *
     * Internal visibility allows [PlaybackManagerFallbackFetchAtomicityTest] to
     * invoke the method directly.
     *
     * Bounded to [FETCH_BOOK_TIMEOUT] rather than the 15s RPC default — this was the "old
     * `getPosition` bug, verbatim": no explicit budget, plus `idempotent = true`, which together let
     * a half-open socket cost up to 15s + 15s on the tap-to-audio path for any book whose audio-file
     * rows haven't synced yet. `idempotent` is now `false`: unlike `getPosition`'s reconcile, a
     * failed fetch here has no fallback (playback preparation fails outright either way), so the
     * auto-retry bought nothing but the doubled worst case that caused the original incident.
     *
     * @return true if fetch + persist succeeded.
     */
    internal suspend fun fetchBookFromServer(bookId: BookId): Boolean =
        when (
            val result =
                channel.call(timeout = FETCH_BOOK_TIMEOUT, idempotent = false) { it.getBook(bookId) }
        ) {
            is AppResult.Success -> {
                val payload = result.data
                logger.info { "Fetched book from server: ${payload.title}" }
                when (val writeResult = bookSyncDomainHandler.onCatchUpItem(payload, isTombstone = false)) {
                    is AppResult.Success -> {
                        logger.debug {
                            "Saved fetched book ${bookId.value} + ${payload.audioFiles.size} audio files to local database"
                        }
                        true
                    }

                    is AppResult.Failure -> {
                        logger.error { "Failed to persist fetched book ${bookId.value}: ${writeResult.error.message}" }
                        false
                    }
                }
            }

            is AppResult.Failure -> {
                // The channel folds a missing-server config and transport faults alike into a typed
                // Failure (and re-raises genuine cancellation), so the on-demand fetch degrades to a
                // logged cache miss — "Never Stranded".
                logger.warn { "Fallback book fetch failed for ${bookId.value} (${result.error.code})" }
                false
            }
        }

    /**
     * Best-effort background caching: fires [DownloadService.downloadBook] when [timeline] isn't
     * fully downloaded and the listener hasn't explicitly deleted this book. Split out of
     * [prepareInternal] purely to keep it under detekt's method-length/complexity budget — no
     * behavioral reason for the split.
     */
    private suspend fun triggerBackgroundDownloadIfNeeded(
        bookId: BookId,
        timeline: PlaybackTimeline,
    ) {
        if (!deviceContext.supportsDownloads) {
            logger.info { "Device does not support downloads, streaming only" }
        } else if (!timeline.isFullyDownloaded && !downloadService.wasExplicitlyDeleted(bookId)) {
            logger.info { "Book not fully downloaded, triggering background download" }
            scope.launch { logBackgroundDownloadFailure(bookId) }
        } else if (!timeline.isFullyDownloaded) {
            logger.info { "Book was explicitly deleted, streaming only (no auto-download)" }
        }
    }

    /**
     * Runs the actual auto-download and logs a failure. Best-effort: this is an auto-triggered
     * cache-ahead, not a user-initiated tap, so a failure here does not surface to the UI the way
     * the explicit download button's `handleDownloadResult` does — the listener would otherwise
     * learn only when they go offline and the book isn't there. Logging is the minimum honesty bar
     * so the failure is diagnosable instead of silently discarded.
     */
    private suspend fun logBackgroundDownloadFailure(bookId: BookId) {
        when (val result = downloadService.downloadBook(bookId)) {
            is AppResult.Success -> {}
            is AppResult.Failure -> {
                logger.warn {
                    "Background download failed for ${bookId.value}: " +
                        "${result.error.code} — ${result.error.message}"
                }
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

/**
 * [PlaybackPreparer.buildTimeline]'s result: the assembled [PlaybackTimeline] plus the
 * server-authoritative resume position ([PreparedPlayback.resumePosition]) from the same
 * `prepare()` call. [serverResumePosition] is null on the fully-downloaded path, where
 * `prepare()` is skipped and the resume point resolves from the local Room row alone.
 */
private data class TimelineBuildResult(
    val timeline: PlaybackTimeline,
    val serverResumePosition: PlaybackPositionSyncPayload?,
)

/**
 * The resume point resolved across the local Room row and the server-authoritative
 * position, carrying only what resume needs: [positionMs] and the [isFinished] flag
 * (finished → start at 0 for re-read).
 */
internal data class ResolvedResumePosition(
    val positionMs: Long,
    val isFinished: Boolean,
    /**
     * When the WINNING side was last played. Carried out of the resolver rather than recomputed by
     * callers: the resolver already picks by this key, so deriving it a second time at the call
     * site would be a second copy of the conflict rule, free to drift from this one.
     */
    val lastPlayedAtMs: Long,
)

/**
 * Reconcile the [local] Room resume row against the [server]-authoritative position from
 * [com.calypsan.listenup.api.PlaybackService.prepare], newer wins by `lastPlayedAt` — the
 * same conflict key the playback-positions sync domain uses.
 *
 * Position is sacred. Device B can open a book before its catch-up drains, so its local
 * row may be stale relative to the position `prepare()` just returned from the server. If
 * the stale local row won here, starting playback would stamp it `lastPlayedAt = now`,
 * making it globally newest and permanently discarding device A's newer progress. Choosing
 * the fresher source at resume time closes that race.
 *
 * Returns null only when both sources are absent (never-played book → resume at 0
 * upstream). When only one is present it wins by default; ties favour the local row (its
 * value is at least as fresh and will re-push to the server on the next save).
 */
internal fun resolveResumePosition(
    local: PlaybackPosition?,
    server: PlaybackPositionSyncPayload?,
): ResolvedResumePosition? =
    when {
        local == null && server == null -> null
        server == null ->
            ResolvedResumePosition(local!!.positionMs, local.isFinished, local.effectiveLastPlayedAtMs)
        local == null -> ResolvedResumePosition(server.positionMs, server.finished, server.lastPlayedAt)
        server.lastPlayedAt > local.effectiveLastPlayedAtMs ->
            ResolvedResumePosition(server.positionMs, server.finished, server.lastPlayedAt)
        else -> ResolvedResumePosition(local.positionMs, local.isFinished, local.effectiveLastPlayedAtMs)
    }

// ========== Type Conversions ==========

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

/**
 * Comma-join the display names of the [contributors] credited with [role] on a
 * book, preferring each cross-ref's `creditedAs` (alias attribution) over the
 * contributor's canonical name. Returns an empty string when no contributor holds
 * the role. Pure — split out from [PlaybackPreparer] so it is unit-testable
 * without a database.
 */
/**
 * The (id, name) refs for a book's contributors in the given [role] — the navigable form of
 * [joinContributorNames], distinct-by-id and preserving credit order, for the player's "Go to
 * Author / Narrator" menu.
 */
internal fun contributorRefs(
    contributors: List<ContributorEntity>,
    contributorRoles: List<BookContributorCrossRef>,
    role: ContributorRole,
): List<PlaybackNavRef> {
    val contributorsById = contributors.associateBy { it.id }
    return contributorRoles
        .filter { it.role == role.apiValue }
        .mapNotNull { crossRef ->
            contributorsById[crossRef.contributorId]?.let { entity ->
                PlaybackNavRef(id = entity.id.value, name = crossRef.creditedAs ?: entity.name)
            }
        }.distinctBy { it.id }
}

internal fun joinContributorNames(
    contributors: List<ContributorEntity>,
    contributorRoles: List<BookContributorCrossRef>,
    role: ContributorRole,
): String {
    val contributorsById = contributors.associateBy { it.id }
    return contributorRoles
        .filter { it.role == role.apiValue }
        .mapNotNull { crossRef ->
            contributorsById[crossRef.contributorId]?.let { entity ->
                crossRef.creditedAs ?: entity.name
            }
        }.distinct()
        .joinToString(", ")
}
