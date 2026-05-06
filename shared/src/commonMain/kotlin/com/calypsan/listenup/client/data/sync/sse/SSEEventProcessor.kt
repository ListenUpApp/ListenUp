@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.sync.sse

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.ActiveSessionEntity
import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookGenreCrossRef
import com.calypsan.listenup.client.data.local.db.BookTagCrossRef
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.SyncState
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserProfileEntity
import com.calypsan.listenup.client.data.local.db.UserStatsEntity
import com.calypsan.listenup.client.data.remote.model.BookResponse
import com.calypsan.listenup.client.data.remote.model.toEntity
import com.calypsan.listenup.client.data.sync.SSEChannelMessage
import com.calypsan.listenup.client.data.sync.SSEEvent
import com.calypsan.listenup.client.data.sync.SessionDaos
import com.calypsan.listenup.client.data.sync.UserDaos
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.domain.repository.CoverDownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Parse ISO 8601 timestamp string to Timestamp, falling back to current time on error.
 */
private fun parseTimestamp(isoString: String): Timestamp =
    try {
        Timestamp(Instant.parse(isoString).toEpochMilliseconds())
    } catch (_: Exception) {
        Timestamp.now()
    }

/**
 * Parse an ISO-8601 timestamp from the wire, returning null on any parse failure.
 *
 * Used for fields where a silent fallback to "now" is a lie of omission — e.g.,
 * `progress_updated.last_played_at`. Callers log and skip the affected row when
 * this returns null; next full sync reconciles.
 *
 * Contrast with [parseTimestamp], which falls back to `Timestamp.now()` for fields
 * where the fallback is semantically acceptable (e.g., session `startedAt` defaults
 * to "now" if malformed).
 */
private fun parseLastPlayedOrNull(isoString: String): Timestamp? =
    try {
        Timestamp(Instant.parse(isoString).toEpochMilliseconds())
    } catch (_: Exception) {
        null
    }

/**
 * Processes real-time Server-Sent Events and applies changes to local database.
 *
 * Handles:
 * - BookCreated/BookUpdated: Upsert book and relationships
 * - BookDeleted: Remove book from database, stop playback if affected, cancel downloads
 * - ScanStarted/ScanCompleted: Log events
 * - Heartbeat: Connection keep-alive
 * - Collection events: Update local collection cache (admin-only)
 * - Tag events: Update local tag cache and book-tag relationships
 *
 * TODO: Class body is above detekt's LargeClass threshold; split into focused handlers is a future task.
 */
@Suppress("LargeClass")
class SSEEventProcessor(
    private val transactionRunner: TransactionRunner,
    private val bookDao: BookDao,
    bookRelationshipDaos: BookRelationshipDaos,
    private val collectionDao: CollectionDao,
    private val shelfDao: ShelfDao,
    userDaos: UserDaos,
    sessionDaos: SessionDaos,
    sseExternalServices: SSEExternalServices,
    private val activityDao: ActivityDao,
    private val coverDownloadRepository: CoverDownloadRepository,
    private val avatarDownloadRepository: AvatarDownloadRepository,
) {
    private val bookContributorDao = bookRelationshipDaos.bookContributorDao
    private val bookSeriesDao = bookRelationshipDaos.bookSeriesDao
    private val tagDao = bookRelationshipDaos.tagDao
    private val genreDao = bookRelationshipDaos.genreDao
    private val audioFileDao = bookRelationshipDaos.audioFileDao
    private val userDao = userDaos.userDao
    private val userProfileDao = userDaos.userProfileDao
    private val userStatsDao = userDaos.userStatsDao
    private val activeSessionDao = sessionDaos.activeSessionDao
    private val listeningEventDao = sessionDaos.listeningEventDao
    private val playbackPositionDao = sessionDaos.playbackPositionDao
    private val sessionRepository = sseExternalServices.sessionRepository
    private val imageDownloader = sseExternalServices.imageDownloader
    private val playbackStateProvider = sseExternalServices.playbackStateProvider
    private val downloadService = sseExternalServices.downloadService
    private val downloadRepository = sseExternalServices.downloadRepository

    private val _accessRevokedEvents = MutableSharedFlow<AccessRevokedEvent>(extraBufferCapacity = 16)

    /**
     * Flow of access revocation events for UI to display notifications.
     * Emitted when a book is deleted while being played or downloaded.
     */
    val accessRevokedEvents: SharedFlow<AccessRevokedEvent> = _accessRevokedEvents.asSharedFlow()

    private val _userDeletedEvent = MutableSharedFlow<UserDeletedInfo>(extraBufferCapacity = 1)

    /**
     * Flow of user deletion events. Emitted when the current user's account is deleted.
     * App should clear auth state and navigate to login when this emits.
     */
    val userDeletedEvent: SharedFlow<UserDeletedInfo> = _userDeletedEvent.asSharedFlow()

    private val _libraryAccessModeChangedEvent =
        MutableSharedFlow<LibraryAccessModeChangedInfo>(extraBufferCapacity = 1)

    /**
     * Flow of library access mode change events.
     * Emitted when an admin changes the library access mode.
     * Clients should refresh their book lists as visibility may have changed.
     */
    val libraryAccessModeChangedEvent: SharedFlow<LibraryAccessModeChangedInfo> =
        _libraryAccessModeChangedEvent.asSharedFlow()

    private val _scanCompletedEvent =
        MutableSharedFlow<ScanCompletedInfo>(extraBufferCapacity = 1)

    /**
     * Flow of library scan completion events.
     * Emitted when a library scan finishes (initial or rescan).
     * Clients should trigger a delta sync to fetch newly scanned books.
     */
    val scanCompletedEvent: SharedFlow<ScanCompletedInfo> =
        _scanCompletedEvent.asSharedFlow()

    private val _isServerScanning = MutableStateFlow(false)

    private val _scanProgress = MutableStateFlow<ScanProgressState?>(null)

    /**
     * Current scan progress, or null when not scanning.
     * Updated in real-time as the server sends progress events.
     */
    val scanProgress: StateFlow<ScanProgressState?> = _scanProgress.asStateFlow()

    /**
     * Whether the server is currently scanning the library.
     * True from ScanStarted until ScanCompleted.
     * UI can use this to show "Scanning your library..." instead of empty state.
     */
    val isServerScanning: StateFlow<Boolean> = _isServerScanning.asStateFlow()

    /**
     * Initialize the server scanning state.
     *
     * Called when SSE connects for the first time to set the initial scan state
     * from the library status API. This handles the case where a scan started
     * before the SSE connection was established (e.g., right after library setup).
     *
     * @param isScanning Whether the server is currently scanning
     */
    fun initializeScanningState(isScanning: Boolean) {
        _isServerScanning.value = isScanning
    }

    /**
     * Process an incoming SSE channel message.
     *
     * Splits wire events (decoded [SSEEvent] variants) from synthetic channel
     * signals (like [SSEChannelMessage.Reconnected]) and dispatches each to its
     * handler. Exceptions are caught and logged; [CancellationException] always
     * rethrows per the error-model rubric.
     */
    suspend fun process(message: SSEChannelMessage) {
        try {
            when (message) {
                is SSEChannelMessage.Wire -> {
                    dispatch(message.event)
                }

                is SSEChannelMessage.Reconnected -> {
                    // Handled by SyncManager - triggers delta sync with disconnectedAt timestamp
                    logger.debug {
                        "SSE: Reconnected message (disconnectedAt=${message.disconnectedAt}), " +
                            "SyncManager will handle delta sync"
                    }
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to process SSE message: $message" }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun dispatch(event: SSEEvent) {
        when (event) {
            is SSEEvent.BookCreated -> {
                handleBookCreated(event)
            }

            is SSEEvent.BookUpdated -> {
                handleBookUpdated(event)
            }

            is SSEEvent.BookDeleted -> {
                handleBookDeleted(event)
            }

            is SSEEvent.ScanStarted -> {
                handleScanStarted(event)
            }

            is SSEEvent.ScanCompleted -> {
                handleScanCompleted(event)
            }

            is SSEEvent.ScanProgress -> {
                handleScanProgress(event)
            }

            is SSEEvent.LibraryAccessModeChanged -> {
                handleLibraryAccessModeChanged(event)
            }

            is SSEEvent.Heartbeat -> { /* keepalive — no-op */ }

            // Admin-only events handled by AdminViewModel/AdminInboxViewModel via EventStreamRepository.
            is SSEEvent.UserPending,
            is SSEEvent.UserApproved,
            is SSEEvent.InboxBookAdded,
            is SSEEvent.InboxBookReleased,
            -> { /* no-op — consumed downstream */ }

            is SSEEvent.UserDeleted -> {
                handleUserDeleted(event)
            }

            is SSEEvent.CollectionCreated -> {
                handleCollectionCreated(event)
            }

            is SSEEvent.CollectionUpdated -> {
                handleCollectionUpdated(event)
            }

            is SSEEvent.CollectionDeleted -> {
                handleCollectionDeleted(event)
            }

            is SSEEvent.CollectionBookAdded -> {
                handleCollectionBookAdded(event)
            }

            is SSEEvent.CollectionBookRemoved -> {
                handleCollectionBookRemoved(event)
            }

            is SSEEvent.ShelfCreated -> {
                handleShelfCreated(event)
            }

            is SSEEvent.ShelfUpdated -> {
                handleShelfUpdated(event)
            }

            is SSEEvent.ShelfDeleted -> {
                handleShelfDeleted(event)
            }

            is SSEEvent.ShelfBookAdded -> {
                handleShelfBookAdded(event)
            }

            is SSEEvent.ShelfBookRemoved -> {
                handleShelfBookRemoved(event)
            }

            is SSEEvent.TagCreated -> {
                handleTagCreated(event)
            }

            is SSEEvent.BookTagAdded -> {
                handleBookTagAdded(event)
            }

            is SSEEvent.BookTagRemoved -> {
                handleBookTagRemoved(event)
            }

            is SSEEvent.ProgressUpdated -> {
                handleProgressUpdated(event)
            }

            is SSEEvent.ProgressDeleted -> {
                handleProgressDeleted(event)
            }

            is SSEEvent.SessionStarted -> {
                handleSessionStarted(event)
            }

            is SSEEvent.SessionEnded -> {
                handleSessionEnded(event)
            }

            is SSEEvent.ReadingSessionUpdated -> {
                handleReadingSessionUpdated(event)
            }

            is SSEEvent.ListeningEventCreated -> {
                handleListeningEventCreated(event)
            }

            is SSEEvent.UserStatsUpdated -> {
                handleUserStatsUpdated(event)
            }

            is SSEEvent.ProfileUpdated -> {
                handleProfileUpdated(event)
            }

            is SSEEvent.ActivityCreated -> {
                handleActivityCreated(event)
            }

            is SSEEvent.TranscodeComplete -> {
                handleTranscodeComplete(event.data)
            }

            is SSEEvent.TranscodeProgress -> {
                logger.debug {
                    "SSE: Transcode progress for job ${event.data.jobId}: ${event.data.progress}%"
                }
            }

            is SSEEvent.TranscodeFailed -> {
                logger.warn {
                    "SSE: Transcode failed for job ${event.data.jobId}: ${event.data.error}"
                }
            }

            is SSEEvent.Unknown -> {
                logger.warn {
                    "SSE: unknown event type '${event.rawType}' at ${event.timestamp}"
                }
            }
        }
    }

    private suspend fun handleBookCreated(event: SSEEvent.BookCreated) {
        val book = event.data.book
        logger.debug { "SSE: Book created - ${book.title}" }
        val entity = book.toEntity()
        val withLocalFields = preserveLocalBookFields(entity)

        transactionRunner.atomically {
            bookDao.upsert(withLocalFields)
            saveBookContributors(book)
            saveBookSeries(book)
            saveBookGenres(book)
            saveBookAudioFiles(book)
        }

        // Structured cover download — queueCoverDownload is non-suspending and owns
        // its own scope, so wrapping in scope.launch here would be a redundant hop.
        coverDownloadRepository.queueCoverDownload(BookId(book.id))
    }

    private suspend fun handleBookUpdated(event: SSEEvent.BookUpdated) {
        val book = event.data.book
        logger.debug { "SSE: Book updated - ${book.title}" }
        val entity = book.toEntity()
        val withLocalFields = preserveLocalBookFields(entity)

        transactionRunner.atomically {
            bookDao.upsert(withLocalFields)
            saveBookContributors(book)
            saveBookSeries(book)
            saveBookGenres(book)
            saveBookAudioFiles(book)
        }

        // See handleBookCreated above for rationale on calling queueCoverDownload directly.
        coverDownloadRepository.queueCoverDownload(BookId(book.id))
    }

    private suspend fun handleBookDeleted(event: SSEEvent.BookDeleted) {
        val payload = event.data
        logger.debug { "SSE: Book deleted - ${payload.bookId}" }
        val bookId = BookId(payload.bookId)

        // Check if this book is currently playing and stop playback
        val isCurrentlyPlaying = playbackStateProvider.currentBookId.value == bookId
        if (isCurrentlyPlaying) {
            logger.info { "Book ${payload.bookId} is currently playing, clearing playback" }
            playbackStateProvider.clearPlayback()
            _accessRevokedEvents.tryEmit(AccessRevokedEvent(bookId))
        }

        // Cancel any in-progress download for this book
        try {
            downloadService.cancelDownload(bookId)
            // Also delete any downloaded files
            downloadService.deleteDownload(bookId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to cancel/delete download for book ${payload.bookId}" }
        }

        // Remove from database
        bookDao.deleteById(bookId)
    }

    private fun handleScanStarted(event: SSEEvent.ScanStarted) {
        logger.debug { "SSE: Library scan started - ${event.data.libraryId}" }
        _isServerScanning.value = true
        _scanProgress.value = null
    }

    private fun handleScanProgress(event: SSEEvent.ScanProgress) {
        val payload = event.data
        logger.debug {
            "SSE: Scan progress - phase=${payload.phase}, ${payload.current}/${payload.total}, " +
                "added=${payload.added}, updated=${payload.updated}, removed=${payload.removed}"
        }
        _scanProgress.value =
            ScanProgressState(
                phase = payload.phase,
                current = payload.current,
                total = payload.total,
                added = payload.added,
                updated = payload.updated,
                removed = payload.removed,
            )
    }

    private suspend fun handleScanCompleted(event: SSEEvent.ScanCompleted) {
        val payload = event.data
        logger.debug {
            "SSE: Library scan completed - added=${payload.booksAdded}, " +
                "updated=${payload.booksUpdated}, removed=${payload.booksRemoved}"
        }

        // Clear scanning flag and progress - UI can now show books or empty state
        _isServerScanning.value = false
        _scanProgress.value = null

        // Emit event so SyncManager can trigger delta sync to fetch newly scanned books
        _scanCompletedEvent.emit(
            ScanCompletedInfo(
                libraryId = payload.libraryId,
                booksAdded = payload.booksAdded,
                booksUpdated = payload.booksUpdated,
                booksRemoved = payload.booksRemoved,
            ),
        )
    }

    private suspend fun handleLibraryAccessModeChanged(event: SSEEvent.LibraryAccessModeChanged) {
        val payload = event.data
        logger.info {
            "SSE: Library access mode changed to ${payload.accessMode} for library ${payload.libraryId}"
        }

        // Emit event for app to handle (trigger delta sync to refresh book lists)
        _libraryAccessModeChangedEvent.emit(
            LibraryAccessModeChangedInfo(
                libraryId = payload.libraryId,
                accessMode = payload.accessMode,
            ),
        )
    }

    /**
     * Preserve local-only fields (palette colors) when upserting a book from SSE.
     *
     * The server doesn't know about locally-extracted cover colors, so SSE events
     * arrive with null palette values. Without this, every book.updated SSE event
     * would wipe the locally-extracted colors used for gradient rendering.
     *
     * Same pattern as BookPuller.preserveLocalColors() for delta sync.
     */
    private suspend fun preserveLocalBookFields(entity: BookEntity): BookEntity {
        val existing = bookDao.getById(entity.id) ?: return entity
        return entity.copy(
            dominantColor = entity.dominantColor ?: existing.dominantColor,
            darkMutedColor = entity.darkMutedColor ?: existing.darkMutedColor,
            vibrantColor = entity.vibrantColor ?: existing.vibrantColor,
        )
    }

    /**
     * Save book-contributor relationships from an SSE event.
     * Replaces existing relationships for the book.
     */
    private suspend fun saveBookContributors(book: BookResponse) {
        val bookId = BookId(book.id)

        bookContributorDao.deleteContributorsForBook(bookId)

        val crossRefs =
            book.contributors.flatMap { contributor ->
                contributor.roles.map { role ->
                    contributor.toEntity(bookId, role)
                }
            }

        if (crossRefs.isNotEmpty()) {
            bookContributorDao.insertAll(crossRefs)
            logger.debug { "SSE: Saved ${crossRefs.size} contributor relationships for book ${book.id}" }
        }
    }

    /**
     * Save book-series relationships from an SSE event.
     * Replaces existing relationships for the book.
     */
    private suspend fun saveBookSeries(book: BookResponse) {
        val bookId = BookId(book.id)

        bookSeriesDao.deleteSeriesForBook(bookId)

        val crossRefs =
            book.seriesInfo.map { seriesInfo ->
                seriesInfo.toEntity(bookId)
            }

        if (crossRefs.isNotEmpty()) {
            bookSeriesDao.insertAll(crossRefs)
            logger.debug { "SSE: Saved ${crossRefs.size} series relationships for book ${book.id}" }
        }
    }

    /**
     * Save book-genre relationships from an SSE event.
     *
     * Server-sent [BookResponse.genres] carries names; resolve them to IDs via
     * [GenreDao.getIdsByNames]. Unresolved names (genres not yet in the local
     * catalog — e.g., SSE fired for a book whose genres haven't been pulled
     * yet) are logged and dropped; the next full sync will reconcile them.
     * Replaces existing junction rows for the book — server is authoritative.
     */
    private suspend fun saveBookGenres(book: BookResponse) {
        val bookId = BookId(book.id)
        genreDao.deleteGenresForBook(bookId)

        val names = book.genres.orEmpty().distinctBy { it.lowercase() }
        if (names.isEmpty()) return

        val nameToId = genreDao.getIdsByNames(names).associate { it.name.lowercase() to it.id }
        val crossRefs =
            names.mapNotNull { name ->
                val id = nameToId[name.lowercase()]
                if (id == null) {
                    logger.warn { "SSE: Genre '$name' not in catalog; skipping for book ${book.id}" }
                    null
                } else {
                    BookGenreCrossRef(bookId = bookId, genreId = id)
                }
            }

        if (crossRefs.isNotEmpty()) {
            genreDao.insertAllBookGenres(crossRefs)
            logger.debug { "SSE: Saved ${crossRefs.size} genre relationships for book ${book.id}" }
        }
    }

    /**
     * Save book audio files from an SSE event.
     *
     * Replaces existing junction rows for the book — server is authoritative.
     * Empty `audioFiles` → just delete existing rows (handles the case where
     * a server update clarifies a book has no playable files).
     */
    private suspend fun saveBookAudioFiles(book: BookResponse) {
        audioFileDao.deleteForBook(book.id)
        val rows =
            book.audioFiles.mapIndexed { idx, af ->
                AudioFileEntity(
                    bookId = BookId(book.id),
                    index = idx,
                    id = af.id,
                    filename = af.filename,
                    format = af.format,
                    codec = af.codec,
                    duration = af.duration,
                    size = af.size,
                )
            }
        if (rows.isNotEmpty()) {
            audioFileDao.upsertAll(rows)
            logger.debug { "SSE: Saved ${rows.size} audio file rows for book ${book.id}" }
        }
    }

    // ========== Collection Event Handlers ==========

    private suspend fun handleCollectionCreated(event: SSEEvent.CollectionCreated) {
        val payload = event.data
        logger.debug { "SSE: Collection created - ${payload.name} (${payload.id})" }
        val now = Timestamp.now()
        collectionDao.upsert(
            CollectionEntity(
                id = payload.id,
                name = payload.name,
                bookCount = payload.bookCount,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun handleCollectionUpdated(event: SSEEvent.CollectionUpdated) {
        val payload = event.data
        logger.debug { "SSE: Collection updated - ${payload.name} (${payload.id})" }
        // Get existing to preserve createdAt, or use now if not found
        val existing = collectionDao.getById(payload.id)
        val createdAt = existing?.createdAt ?: Timestamp.now()
        collectionDao.upsert(
            CollectionEntity(
                id = payload.id,
                name = payload.name,
                bookCount = payload.bookCount,
                createdAt = createdAt,
                updatedAt = Timestamp.now(),
            ),
        )
    }

    private suspend fun handleCollectionDeleted(event: SSEEvent.CollectionDeleted) {
        val payload = event.data
        logger.debug { "SSE: Collection deleted - ${payload.name} (${payload.id})" }
        collectionDao.deleteById(payload.id)
    }

    private suspend fun handleCollectionBookAdded(event: SSEEvent.CollectionBookAdded) {
        val payload = event.data
        logger.debug { "SSE: Book ${payload.bookId} added to collection ${payload.collectionName}" }
        // Update the book count for the collection
        val existing = collectionDao.getById(payload.collectionId)
        if (existing != null) {
            collectionDao.upsert(
                existing.copy(
                    bookCount = existing.bookCount + 1,
                    updatedAt = Timestamp.now(),
                ),
            )
        }
    }

    private suspend fun handleCollectionBookRemoved(event: SSEEvent.CollectionBookRemoved) {
        val payload = event.data
        logger.debug { "SSE: Book ${payload.bookId} removed from collection ${payload.collectionName}" }
        // Update the book count for the collection
        val existing = collectionDao.getById(payload.collectionId)
        if (existing != null) {
            collectionDao.upsert(
                existing.copy(
                    bookCount = (existing.bookCount - 1).coerceAtLeast(0),
                    updatedAt = Timestamp.now(),
                ),
            )
        }
    }

    // ========== Shelf Event Handlers ==========

    private suspend fun handleShelfCreated(event: SSEEvent.ShelfCreated) {
        val payload = event.data
        logger.debug { "SSE: Shelf created - ${payload.name} (${payload.id})" }
        shelfDao.upsert(
            ShelfEntity(
                id = payload.id,
                name = payload.name,
                description = payload.description,
                ownerId = payload.ownerId,
                ownerDisplayName = payload.ownerDisplayName,
                ownerAvatarColor = payload.ownerAvatarColor,
                bookCount = payload.bookCount,
                totalDurationSeconds = 0, // Will be updated on first detail view
                createdAt = parseTimestamp(payload.createdAt),
                updatedAt = parseTimestamp(payload.updatedAt),
            ),
        )
    }

    private suspend fun handleShelfUpdated(event: SSEEvent.ShelfUpdated) {
        val payload = event.data
        logger.debug { "SSE: Shelf updated - ${payload.name} (${payload.id})" }
        // Get existing to preserve totalDurationSeconds
        val existing = shelfDao.getById(payload.id)
        shelfDao.upsert(
            ShelfEntity(
                id = payload.id,
                name = payload.name,
                description = payload.description,
                ownerId = payload.ownerId,
                ownerDisplayName = payload.ownerDisplayName,
                ownerAvatarColor = payload.ownerAvatarColor,
                bookCount = payload.bookCount,
                totalDurationSeconds = existing?.totalDurationSeconds ?: 0,
                createdAt = parseTimestamp(payload.createdAt),
                updatedAt = parseTimestamp(payload.updatedAt),
            ),
        )
    }

    private suspend fun handleShelfDeleted(event: SSEEvent.ShelfDeleted) {
        val payload = event.data
        logger.debug { "SSE: Shelf deleted - ${payload.id}" }
        shelfDao.deleteById(payload.id)
    }

    private suspend fun handleShelfBookAdded(event: SSEEvent.ShelfBookAdded) {
        val payload = event.data
        logger.debug { "SSE: Book ${payload.bookId} added to shelf ${payload.shelfId}" }
        // Update the book count for the shelf
        val existing = shelfDao.getById(payload.shelfId)
        if (existing != null) {
            shelfDao.upsert(
                existing.copy(
                    bookCount = payload.bookCount,
                    updatedAt = Timestamp.now(),
                ),
            )
        }
    }

    private suspend fun handleShelfBookRemoved(event: SSEEvent.ShelfBookRemoved) {
        val payload = event.data
        logger.debug { "SSE: Book ${payload.bookId} removed from shelf ${payload.shelfId}" }
        // Update the book count for the shelf
        val existing = shelfDao.getById(payload.shelfId)
        if (existing != null) {
            shelfDao.upsert(
                existing.copy(
                    bookCount = payload.bookCount,
                    updatedAt = Timestamp.now(),
                ),
            )
        }
    }

    // ========== Tag Event Handlers ==========

    private suspend fun handleTagCreated(event: SSEEvent.TagCreated) {
        val payload = event.data
        logger.debug { "SSE: Tag created - ${payload.slug} (${payload.id})" }
        tagDao.upsert(
            TagEntity(
                id = payload.id,
                slug = payload.slug,
                bookCount = payload.bookCount,
                createdAt = Timestamp.now(),
            ),
        )
    }

    private suspend fun handleBookTagAdded(event: SSEEvent.BookTagAdded) {
        val payload = event.data
        val tag = payload.tag
        logger.debug { "SSE: Tag ${tag.slug} added to book ${payload.bookId}" }

        // Ensure the tag exists locally (upsert with updated book count)
        tagDao.upsert(
            TagEntity(
                id = tag.id,
                slug = tag.slug,
                bookCount = tag.bookCount,
                createdAt = Timestamp.now(),
            ),
        )

        // Add the book-tag relationship
        tagDao.insertBookTag(
            BookTagCrossRef(
                bookId = BookId(payload.bookId),
                tagId = tag.id,
            ),
        )

        // Touch the book's updatedAt to trigger UI refresh
        try {
            bookDao.touchUpdatedAt(BookId(payload.bookId), Timestamp.now())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to touch book ${payload.bookId} after tag added" }
        }
    }

    private suspend fun handleBookTagRemoved(event: SSEEvent.BookTagRemoved) {
        val payload = event.data
        val tag = payload.tag
        logger.debug { "SSE: Tag ${tag.slug} removed from book ${payload.bookId}" }

        // Remove the book-tag relationship
        tagDao.deleteBookTag(BookId(payload.bookId), tag.id)

        // Update the tag's book count
        val existingTag = tagDao.getById(tag.id)
        if (existingTag != null) {
            tagDao.upsert(
                existingTag.copy(
                    bookCount = tag.bookCount,
                ),
            )
        }

        // Touch the book's updatedAt to trigger UI refresh
        try {
            bookDao.touchUpdatedAt(BookId(payload.bookId), Timestamp.now())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to touch book ${payload.bookId} after tag removed" }
        }
    }

    // ========== Listening Event Handlers ==========

    private suspend fun handleProgressUpdated(event: SSEEvent.ProgressUpdated) {
        val payload = event.data
        logger.info {
            "SSE: Progress updated for book ${payload.bookId} - " +
                "${(payload.progress * 100).toInt()}%, position=${payload.currentPositionMs}ms (from another device)"
        }

        val bookId = BookId(payload.bookId)

        // Skip if this book is currently playing locally — local playback is authoritative
        if (playbackStateProvider.currentBookId.value == bookId) {
            logger.info {
                "SSE: Skipping progress update for ${payload.bookId} — book is currently playing locally"
            }
            return
        }

        val lastPlayedAt =
            parseLastPlayedOrNull(payload.lastPlayedAt) ?: run {
                logger.warn {
                    "SSE: malformed last_played_at '${payload.lastPlayedAt}' for book ${payload.bookId} — " +
                        "skipping row; next sync will reconcile"
                }
                return
            }
        val lastPlayedAtMs = lastPlayedAt.epochMillis
        val finishedAtMs = payload.finishedAt?.let { parseTimestamp(it).epochMillis }
        val startedAtMs = payload.startedAt?.let { parseTimestamp(it).epochMillis }

        try {
            // Only update if the remote progress is newer than local
            val existing = playbackPositionDao.get(bookId)
            if (existing != null && (existing.lastPlayedAt ?: 0L) >= lastPlayedAtMs) {
                logger.debug {
                    "SSE: Skipping progress update for ${payload.bookId} - local is newer " +
                        "(local=${existing.lastPlayedAt}, remote=$lastPlayedAtMs)"
                }
                return
            }

            val merged =
                existing?.copy(
                    positionMs = payload.currentPositionMs,
                    isFinished = payload.isFinished,
                    lastPlayedAt = lastPlayedAtMs,
                    updatedAt = lastPlayedAtMs,
                    // TODO(W6): syncedAt semantically means "last server-sync time"; assigning
                    // lastPlayedAtMs is a pre-SP1 carry-over. Revisit during Phase C
                    // ProgressPuller unification.
                    syncedAt = lastPlayedAtMs,
                    // Preserve un-carried timestamps; overwrite only when event provides a value.
                    // Server omits `finishedAt` when null (Go `json:"finished_at,omitempty"`), so
                    // wire-absence means "no change" — not "clear." Un-mark-finished propagates
                    // via ProgressDeleted, not this event. Same contract applies to `startedAt`.
                    finishedAt = finishedAtMs ?: existing.finishedAt,
                    startedAt = startedAtMs ?: existing.startedAt,
                    // playbackSpeed and hasCustomSpeed are preserved implicitly by .copy()
                ) ?: PlaybackPositionEntity(
                    bookId = bookId,
                    positionMs = payload.currentPositionMs,
                    playbackSpeed = 1.0f,
                    hasCustomSpeed = false,
                    isFinished = payload.isFinished,
                    lastPlayedAt = lastPlayedAtMs,
                    updatedAt = lastPlayedAtMs,
                    syncedAt = lastPlayedAtMs,
                    finishedAt = finishedAtMs,
                    startedAt = startedAtMs,
                )

            playbackPositionDao.save(merged)
            logger.info { "SSE: Updated local position for ${payload.bookId} to ${payload.currentPositionMs}ms" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "SSE: Failed to update progress for ${payload.bookId}" }
        }
    }

    private suspend fun handleProgressDeleted(event: SSEEvent.ProgressDeleted) {
        val payload = event.data
        logger.info { "SSE: Progress deleted for book ${payload.bookId} (from another device)" }
        try {
            playbackPositionDao.delete(BookId(payload.bookId))
            logger.debug { "SSE: Deleted local progress for book ${payload.bookId}" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "SSE: Failed to delete progress for book ${payload.bookId}" }
        }
    }

    private suspend fun handleReadingSessionUpdated(event: SSEEvent.ReadingSessionUpdated) {
        val payload = event.data
        logger.info {
            "SSE: Reading session ${payload.sessionId} updated for book ${payload.bookId} - " +
                "completed=${payload.isCompleted}"
        }
        // Refresh reading sessions cache in Room for offline-first display.
        // When the user later visits this book's detail page, readers data is already available.
        try {
            sessionRepository.refreshBookReaders(payload.bookId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "SSE: Failed to refresh readers for book ${payload.bookId}" }
        }
    }

    private suspend fun handleListeningEventCreated(event: SSEEvent.ListeningEventCreated) {
        val payload = event.data
        logger.info {
            "SSE: Listening event ${payload.id} received for book ${payload.bookId} (from another device)"
        }

        // Convert ISO timestamps to epoch ms
        val startedAtMs = parseTimestamp(payload.startedAt).epochMillis
        val endedAtMs = parseTimestamp(payload.endedAt).epochMillis
        val createdAtMs = parseTimestamp(payload.createdAt).epochMillis

        // Save to Room - this triggers stats to auto-update via Flow
        val entity =
            ListeningEventEntity(
                id = payload.id,
                bookId = payload.bookId,
                startPositionMs = payload.startPositionMs,
                endPositionMs = payload.endPositionMs,
                startedAt = startedAtMs,
                endedAt = endedAtMs,
                playbackSpeed = payload.playbackSpeed,
                deviceId = payload.deviceId,
                syncState = SyncState.SYNCED, // Already synced since it came from server
                createdAt = createdAtMs,
            )

        try {
            listeningEventDao.upsert(entity)
            logger.debug { "SSE: Saved listening event ${payload.id} to Room" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "SSE: Failed to save listening event ${payload.id}" }
        }
    }

    // ========== Activity Event Handlers ==========

    /**
     * Handle activity created SSE event.
     *
     * Stores the activity in Room for offline-first activity feed display.
     * Activities are denormalized with all display data (user name, avatar, book info)
     * to avoid joins and enable instant offline display.
     */
    private suspend fun handleActivityCreated(event: SSEEvent.ActivityCreated) {
        val payload = event.data
        logger.debug { "SSE: Activity created - ${payload.type} by ${payload.userDisplayName}" }

        val createdAtMs = parseTimestamp(payload.createdAt).epochMillis

        val entity =
            ActivityEntity(
                id = payload.id,
                userId = payload.userId,
                type = payload.type,
                createdAt = createdAtMs,
                userDisplayName = payload.userDisplayName,
                userAvatarColor = payload.userAvatarColor,
                userAvatarType = payload.userAvatarType,
                userAvatarValue = payload.userAvatarValue,
                bookId = payload.bookId,
                bookTitle = payload.bookTitle,
                bookAuthorName = payload.bookAuthorName,
                bookCoverPath = payload.bookCoverPath,
                isReread = payload.isReread,
                durationMs = payload.durationMs,
                milestoneValue = payload.milestoneValue,
                milestoneUnit = payload.milestoneUnit,
                shelfId = payload.shelfId,
                shelfName = payload.shelfName,
            )

        try {
            activityDao.upsert(entity)
            logger.debug { "SSE: Saved activity ${payload.id} to Room" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "SSE: Failed to save activity ${payload.id}" }
        }
    }

    // ========== Active Session Event Handlers ==========

    /**
     * Handle session started SSE event.
     *
     * Stores the active session in Room for "What Others Are Listening To" feature.
     * The session is joined with UserProfileEntity and BookEntity for display.
     *
     * Also ensures the user's avatar image is downloaded locally if they have one,
     * so the avatar displays correctly in offline-first UI.
     */
    private suspend fun handleSessionStarted(event: SSEEvent.SessionStarted) {
        val payload = event.data
        logger.debug { "SSE: Session started - ${payload.sessionId} for user ${payload.userId}" }

        // Queue avatar download via the repository (owns its own scope — no fire-and-forget
        // launch inside a suspend handler). The repo internally skips if the file is cached.
        val userProfile = userProfileDao.getById(payload.userId)
        if (userProfile != null && userProfile.avatarType == "image") {
            avatarDownloadRepository.queueAvatarDownload(payload.userId)
        }

        val startedAtMs = parseTimestamp(payload.startedAt).epochMillis
        val now = Timestamp.now().epochMillis

        activeSessionDao.upsert(
            ActiveSessionEntity(
                sessionId = payload.sessionId,
                userId = payload.userId,
                bookId = payload.bookId,
                startedAt = startedAtMs,
                updatedAt = now,
            ),
        )
    }

    /**
     * Handle session ended SSE event.
     *
     * Removes the session from Room.
     */
    private suspend fun handleSessionEnded(event: SSEEvent.SessionEnded) {
        val payload = event.data
        logger.debug { "SSE: Session ended - ${payload.sessionId}" }
        activeSessionDao.deleteBySessionId(payload.sessionId)
    }

    // ========== User Stats Event Handlers ==========

    /**
     * Handle user stats updated SSE event.
     *
     * Updates the cached user stats in Room for leaderboard display.
     * This enables offline-first leaderboard with real-time updates.
     */
    private suspend fun handleUserStatsUpdated(event: SSEEvent.UserStatsUpdated) {
        val payload = event.data
        logger.debug { "SSE: User stats updated - ${payload.displayName} (${payload.userId})" }

        val entity =
            UserStatsEntity(
                userId = payload.userId,
                displayName = payload.displayName,
                avatarColor = payload.avatarColor,
                avatarType = payload.avatarType,
                avatarValue = payload.avatarValue,
                totalTimeMs = payload.totalTimeMs,
                totalBooks = payload.totalBooks,
                currentStreak = payload.currentStreak,
                updatedAt = Timestamp.now().epochMillis,
            )

        try {
            userStatsDao.upsert(entity)
            logger.debug { "SSE: Cached user stats for ${payload.userId}" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "SSE: Failed to cache user stats for ${payload.userId}" }
        }
    }

    // ========== Profile Event Handlers ==========

    /**
     * Handle profile updated SSE event.
     *
     * For current user's profile:
     * - Updates local UserEntity so UI observing it sees the new avatar
     *
     * For any user (including current user):
     * - Caches the user profile in UserProfileEntity for offline access
     * - Downloads the avatar image locally for offline access
     *
     * This enables fully offline profile viewing for any user whose
     * profile update was received while online.
     */
    @Suppress("ThrowsCount") // Per-field failures each throw; CancellationException rethrow adds one more
    private suspend fun handleProfileUpdated(event: SSEEvent.ProfileUpdated) {
        val payload = event.data
        logger.info { "SSE: Profile updated - ${payload.displayName} (${payload.userId})" }

        val currentUser = userDao.getCurrentUser()
        val isCurrentUser = currentUser != null && payload.userId == currentUser.id.value

        // IMPORTANT: Download/delete avatar FIRST, before updating userDao
        // This ensures the local file exists when the UI Flow emits after userDao update.
        // If we update userDao first, the UI checks for the file before it's downloaded.
        if (payload.avatarType == "image" && payload.avatarValue != null) {
            avatarDownloadRepository.queueAvatarForceRefresh(payload.userId)
            logger.info { "SSE: Queued force-refresh avatar for user ${payload.userId}" }
        } else if (payload.avatarType == "auto") {
            // User reverted to auto avatar, delete the local image file
            avatarDownloadRepository.deleteAvatar(payload.userId)
            logger.info { "SSE: Deleted local avatar for user ${payload.userId} (reverted to auto)" }
        }

        // Cache user profile for offline display (for ALL users, not just current)
        try {
            userProfileDao.upsert(
                UserProfileEntity(
                    id = payload.userId,
                    displayName = payload.displayName,
                    avatarType = payload.avatarType,
                    avatarValue = payload.avatarValue,
                    avatarColor = payload.avatarColor,
                    updatedAt = Timestamp.now().epochMillis,
                ),
            )
            logger.debug { "SSE: Cached user profile for ${payload.userId}" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "SSE: Failed to cache user profile for ${payload.userId}" }
        }

        // Now update local UserEntity for current user - avatar file is already ready
        // Note: isCurrentUser being true implies currentUser != null (see definition above)
        if (isCurrentUser) {
            try {
                // Update name fields
                userDao.updateName(
                    userId = payload.userId,
                    firstName = payload.firstName,
                    lastName = payload.lastName,
                    displayName = payload.displayName,
                    updatedAt = Timestamp.now().epochMillis,
                )
                // Update avatar
                userDao.updateAvatar(
                    userId = payload.userId,
                    avatarType = payload.avatarType,
                    avatarValue = payload.avatarValue,
                    avatarColor = payload.avatarColor,
                    updatedAt = Timestamp.now().epochMillis,
                )
                // Update tagline
                if (payload.tagline != null || currentUser.tagline != null) {
                    userDao.updateTagline(
                        userId = payload.userId,
                        tagline = payload.tagline,
                        updatedAt = Timestamp.now().epochMillis,
                    )
                }
                logger.info {
                    "SSE: Updated local UserEntity with profile changes - " +
                        "name=${payload.displayName}, avatar=${payload.avatarType}/${payload.avatarValue}"
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "SSE: Failed to update local UserEntity for profile event" }
            }
        }
    }

    // ========== User Deletion Handler ==========

    /**
     * Handle user deleted SSE event.
     *
     * Emits to userDeletedEvent flow so the app can clear auth state
     * and navigate to login. The SSE connection will be closed after this.
     */
    private suspend fun handleUserDeleted(event: SSEEvent.UserDeleted) {
        val payload = event.data
        logger.warn { "SSE: User account deleted - ${payload.userId}, reason: ${payload.reason}" }

        // Emit event for app to handle (clear auth, navigate to login)
        _userDeletedEvent.emit(
            UserDeletedInfo(
                userId = payload.userId,
                reason = payload.reason,
            ),
        )
    }

    // ========== Transcode Event Handlers ==========

    private suspend fun handleTranscodeComplete(
        payload: com.calypsan.listenup.client.data.sync.TranscodeCompletePayload,
    ) {
        logger.info { "SSE: Transcode complete for audioFileId=${payload.audioFileId} (jobId=${payload.jobId})" }
        downloadRepository.resumeForAudioFile(payload.audioFileId)
    }
}

/**
 * Event emitted when access to a book is revoked while playing.
 * UI should display a notification that "This book is no longer available".
 */
data class AccessRevokedEvent(
    val bookId: BookId,
)

/**
 * Event emitted when the current user's account is deleted.
 * The app should clear auth state and navigate to login.
 */
data class UserDeletedInfo(
    val userId: String,
    val reason: String?,
)

/**
 * Event emitted when the library access mode changes.
 * Clients should refresh their book lists as visibility may have changed.
 */
data class LibraryAccessModeChangedInfo(
    val libraryId: String,
    val accessMode: String,
)

/**
 * Event emitted when a library scan completes.
 * Clients should trigger a delta sync to fetch newly scanned books.
 */
data class ScanCompletedInfo(
    val libraryId: String,
    val booksAdded: Int,
    val booksUpdated: Int,
    val booksRemoved: Int,
)

/**
 * Interface for playback state access needed by SSEEventProcessor.
 * Implemented by PlaybackManager to avoid direct dependency on final class.
 */
interface PlaybackStateProvider {
    /**
     * The currently playing book ID, or null if nothing is playing.
     */
    val currentBookId: StateFlow<BookId?>

    /**
     * Clear current playback state when access is revoked.
     */
    fun clearPlayback()
}

/**
 * Current state of a library scan in progress.
 * Null when no scan is running.
 */
data class ScanProgressState(
    val phase: String,
    val current: Int,
    val total: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
) {
    /**
     * Human-readable phase name.
     */
    val phaseDisplayName: String
        get() =
            when (phase) {
                "walking" -> "Discovering files"
                "grouping" -> "Organizing"
                "analyzing" -> "Analyzing"
                "resolving" -> "Processing"
                "diffing" -> "Syncing"
                "applying" -> "Syncing"
                "complete" -> "Finishing up"
                else -> phase.replaceFirstChar { it.uppercase() }
            }

    /**
     * Progress as a fraction (0.0 to 1.0), or null if total is 0.
     */
    val progressFraction: Float?
        get() = if (total > 0) current.toFloat() / total.toFloat() else null

    /**
     * Summary of changes so far (e.g., "3 added, 1 updated").
     */
    val changesSummary: String?
        get() {
            val parts = mutableListOf<String>()
            if (added > 0) parts.add("$added added")
            if (updated > 0) parts.add("$updated updated")
            if (removed > 0) parts.add("$removed removed")
            return parts.joinToString(", ").ifEmpty { null }
        }
}
