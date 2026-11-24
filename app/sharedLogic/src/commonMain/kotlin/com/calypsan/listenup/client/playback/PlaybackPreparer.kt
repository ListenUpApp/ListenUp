package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.valueOrNull
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.heldRefusal
import com.calypsan.listenup.client.data.local.db.toAudioFile
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

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
 * the tap-to-audio path for tens of seconds before the outer `prepare()` catch-all folds it to a failure
 * and the listener sees "couldn't start playback" — the same outcome, arrived at far faster.
 */
private val FETCH_BOOK_TIMEOUT = 5.seconds

/**
 * Stateless playback-preparation pipeline: turns a [BookId] into a
 * [PreparedPlayback] value (auth token refresh, book + audio-file load with
 * server fallback, timeline build, chapter load, resume-position resolution).
 *
 * Holds no mutable playback state. [PlaybackManagerImpl] constructs one
 * internally and delegates [PlaybackManager.prepareForPlayback] to it; the iOS
 * native player calls [prepareOrNull] via Koin.
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
    private val timelineBuilder =
        PlaybackTimelineBuilder(
            downloadService = downloadService,
            prepareRepository = prepareRepository,
        )

    /**
     * Prepare playback for [bookId] — **the one choke point every playback start passes through**:
     * Android (Book Detail, Continue Listening, deep links, Android Auto, voice, resumption), web,
     * desktop, and iOS (Book Detail, CarPlay, App Intents, context menu) all funnel here.
     *
     * A book held for review is refused first, with [BookError.HeldForReview] — before any network
     * call or download, and even when its audio is already on the device: a held book is triage-only
     * until released. Every other failure is a [PlaybackError.CouldNotStart] carrying the cause in
     * `debugInfo`.
     *
     * Offline-first: if every audio file is already downloaded, the server prepare endpoint is skipped
     * entirely and local paths are used. Otherwise, a single call to [PlaybackService.prepare] fetches
     * signed streaming URLs for all files.
     *
     * [start] is where the listener asked to start: the saved position ([PlaybackStart.Resume]), a place they
     * picked ([PlaybackStart.At], e.g. a chapter's start), or moves they made before Play
     * ([PlaybackStart.Moved]), replayed from the reconciled position. A picked place or a move skips
     * auto-rewind and the finished→0 reset; a finished book still begins a new listen-through.
     */
    suspend fun prepare(
        bookId: BookId,
        start: PlaybackStart,
    ): AppResult<PreparedPlayback> =
        try {
            val refusal = bookDao.heldRefusal(bookId)
            if (refusal != null) {
                logger.info { "Refusing to play ${bookId.value}: held for review" }
                AppResult.Failure(refusal)
            } else {
                prepareInternal(bookId, start)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An escaping throw — e.g. a transport exception from the streaming-prepare RPC when the
            // book isn't downloaded — is folded to a typed failure and logged with its real cause.
            logger.error(e) { "Playback prepare failed for ${bookId.value}" }
            AppResult.Failure(PlaybackError.CouldNotStart(debugInfo = "bookId=${bookId.value}: $e"))
        }

    /**
     * iOS-safe accessor: the [PreparedPlayback] or `null` on any refusal or failure (logged). Use from
     * Swift — never `await` the `AppResult`-returning [prepare] (Swift Export bridge trap). The held
     * refusal happens inside [prepare], so this path is gated identically.
     */
    suspend fun prepareOrNull(
        bookId: BookId,
        startAtMs: Long?,
    ): PreparedPlayback? =
        prepare(bookId, startAtMs?.let(PlaybackStart::At) ?: PlaybackStart.Resume).valueOrNull {
            logger.warn { "prepareOrNull(${bookId.value}): ${it.code} ${it.debugInfo.orEmpty()}" }
        }

    private suspend fun prepareInternal(
        bookId: BookId,
        start: PlaybackStart,
    ): AppResult<PreparedPlayback> {
        logger.info { "Preparing playback for book: ${bookId.value}" }

        // 1. Get server URL — the ACTIVE url, so audio streams from the remote host after a roam
        //    off-LAN switches the active url away from the (now-unreachable) local one.
        val serverUrl = serverConfig.getActiveUrl()?.value
        if (serverUrl == null) {
            logger.error { "No server URL configured" }
            return couldNotStart(bookId, "no server URL configured")
        }

        // 2. Get book with contributors from database
        val bookWithContributors = bookDao.getByIdWithContributors(bookId)
        if (bookWithContributors == null) {
            logger.error { "Book not found: ${bookId.value}" }
            return couldNotStart(bookId, "book not found")
        }
        val book = bookWithContributors.book

        val bookAuthor = deriveAuthorName(bookWithContributors)
        val bookNarrator = deriveNarratorName(bookWithContributors)

        // Get series name + id (first series if multiple) — id feeds the player's "Go to Series".
        val firstSeries = bookWithContributors.series.firstOrNull()
        val seriesName = firstSeries?.name
        val seriesId = firstSeries?.run { id.value }
        val authorRefs =
            contributorRefs(
                bookWithContributors.contributors,
                bookWithContributors.contributorRoles,
                ContributorRole.AUTHOR,
            )
        val narratorRefs =
            contributorRefs(
                bookWithContributors.contributors,
                bookWithContributors.contributorRoles,
                ContributorRole.NARRATOR,
            )

        // Get cover path (if exists on disk)
        val coverPath =
            if (imageStorage.exists(bookId)) {
                imageStorage.getCoverPath(bookId)
            } else {
                null
            }

        // 3. Load audio files from the junction. Fallback-fetch if empty locally.
        val audioFileEntities =
            when (val filesResult = loadAudioFiles(bookId)) {
                is AppResult.Success -> filesResult.data
                is AppResult.Failure -> return filesResult
            }

        logAudioFileDiagnostics(bookId, audioFileEntities)

        // 4. Build PlaybackTimeline — offline-first via signed RPC URLs
        val domainAudioFiles = audioFileEntities.map { it.toAudioFile() }
        val buildResult =
            when (val built = timelineBuilder.build(bookId, domainAudioFiles, serverUrl)) {
                is AppResult.Success -> built.data
                is AppResult.Failure -> return built
            }
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

        val resumePositionMs =
            startPositionMs(
                start = start,
                resolved = resolved,
                chapters = chapters,
                totalDurationMs = timeline.totalDurationMs,
            )
        // Starting a finished book over is a new listen-through: clear the flag now, or every later
        // open would take the re-read branch again and throw away how far the listener got.
        // The flag alone is right here: ProgressTracker.isInEndCredits sets it for a book left in its credits.
        if (resolved?.isFinished == true) progressTracker.startRelisten(bookId)

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

        logResumeDiagnostics(
            timeline = timeline,
            resumePositionMs = resumePositionMs,
            resumeSpeed = resumeSpeed,
            hasCustomSpeed = savedPosition?.hasCustomSpeed == true,
        )

        // 7. Trigger background download if not fully downloaded (best-effort caching)
        triggerBackgroundDownloadIfNeeded(bookId, timeline)

        return AppResult.Success(
            PreparedPlayback(
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
                serverConversion = buildResult.serverConversion,
            ),
        )
    }

    /**
     * Fetch the server-authoritative resume position for the fully-downloaded path (where
     * [PlaybackTimelineBuilder.build] skips `prepare()` and never sees it).
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
     * Where [start] begins, given the reconciled [resolved] row. A position the listener named (a chapter tap) is
     * exact: no auto-rewind, no finished→0 reset. Moves made before Play (a restored book's skips) replay from the
     * reconciled position, never from the stale local row they were shown against, so a skip cannot resume there
     * and overwrite another device's newer progress.
     */
    private fun startPositionMs(
        start: PlaybackStart,
        resolved: ResolvedResumePosition?,
        chapters: List<Chapter>,
        totalDurationMs: Long,
    ): Long =
        when (start) {
            PlaybackStart.Resume -> {
                resumeStartPositionMs(resolved)
            }

            is PlaybackStart.At -> {
                start.positionMs.coerceIn(0L, (totalDurationMs - 1).coerceAtLeast(0L))
            }

            is PlaybackStart.Moved -> {
                landingMs(
                    // The flag alone is right here: ProgressTracker.isInEndCredits sets it for a book left in its credits.
                    fromMs = if (resolved == null || resolved.isFinished) 0L else resolved.positionMs,
                    moves = start.moves,
                    chapters = chapters,
                    durationMs = totalDurationMs,
                )
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
        // The flag alone is right here: ProgressTracker.isInEndCredits sets it for a book left in its credits.
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

    private suspend fun loadAudioFiles(
        bookId: BookId,
    ): AppResult<List<com.calypsan.listenup.client.data.local.db.AudioFileEntity>> {
        var entities = audioFileDao.getForBook(bookId.value)
        if (entities.isEmpty()) {
            logger.info { "No audio files for book: ${bookId.value}, fetching from server..." }
            val fetched = fetchBookFromServer(bookId)
            if (!fetched) {
                logger.error { "Failed to fetch book from server: ${bookId.value}" }
                return couldNotStart(bookId, "book fetch from server failed")
            }
            entities = audioFileDao.getForBook(bookId.value)
            if (entities.isEmpty()) {
                logger.error { "Audio files still empty after fallback fetch for ${bookId.value}" }
                return couldNotStart(bookId, "audio files still empty after fallback fetch")
            }
        }
        return AppResult.Success(entities)
    }

    private fun logResumeDiagnostics(
        timeline: PlaybackTimeline,
        resumePositionMs: Long,
        resumeSpeed: Float,
        hasCustomSpeed: Boolean,
    ) {
        logger.debug {
            "Resume position: ${resumePositionMs}ms, speed: ${resumeSpeed}x (hasCustomSpeed=$hasCustomSpeed)"
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
    }

    private fun couldNotStart(
        bookId: BookId,
        reason: String,
    ): AppResult.Failure = AppResult.Failure(PlaybackError.CouldNotStart(debugInfo = "bookId=${bookId.value}: $reason"))

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
        // Two independent gates: form factor (should THIS KIND of device download at all —
        // TV/Auto/Xr never should) and backend capability (CAN the DownloadService actually
        // finish one). A coarse-pointer mobile browser answers yes to the first and no to the
        // second — see DownloadService.supportsDownloads — so neither alone is sufficient.
        if (!deviceContext.supportsDownloads || !downloadService.supportsDownloads) {
            logger.info { "Device/backend does not support downloads, streaming only" }
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
