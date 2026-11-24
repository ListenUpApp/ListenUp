package com.calypsan.listenup.server.services

import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverInfo
import com.calypsan.listenup.server.cover.LiveCover
import com.calypsan.listenup.server.cover.ManagedCoverFiles
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.FrameCapture
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import com.calypsan.listenup.server.sync.emitInPublishOrder
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.files.Path

/**
 * Server-side repository for the books aggregate, over SQLDelight.
 *
 * Extends the [SqlSyncableRepository] substrate, owning the multi-table read/write
 * for a book + its contributors + series + chapters + audio files. The substrate
 * orchestrates revision bumping and change-bus publication; this class implements
 * [readPayload] and [writePayload] and delegates scan ingest, cover resolution/mutations,
 * move/path operations, and soft-delete/revival lifecycle to focused collaborators in this
 * package.
 *
 * `idAsString(BookId) = id.value` is load-bearing — the substrate's default
 * `toString()` on a value class returns `"BookId(value=foo)"`, which would
 * corrupt every column the id is written to.
 *
 * **Database handles.** [db] (the SQLDelight [ListenUpDatabase]) backs every book read/write,
 * the genre junction writes, and the cover collaborator [ManagedCoverFiles].
 * [driver] (the shared SQLDelight [SqlDriver] behind [db]) runs the access-filtered [pullSince]
 * / [digest] id reads engine-neutrally — the runtime-built [SqlFragment] access subquery carries
 * plain raw args, so it splices over the driver inside the same `suspendTransaction(db)`.
 */
class BookRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    contributorRepository: ContributorRepository,
    seriesRepository: SeriesRepository,
    genreRepository: GenreRepository,
    analyzedBookMapper: AnalyzedBookMapper = AnalyzedBookMapper(),
    clock: Clock = Clock.System,
    private val collectionBookRepository: com.calypsan.listenup.server.sync.CollectionBookRepository? = null,
    tagRepository: com.calypsan.listenup.server.sync.TagRepository? = null,
    bookTagRepository: com.calypsan.listenup.server.sync.BookTagRepository? = null,
    bookMoodRepository: com.calypsan.listenup.server.sync.BookMoodRepository? = null,
    entityRepository: com.calypsan.listenup.server.sync.EntityRepository? = null,
    worldEventRepository: com.calypsan.listenup.server.sync.WorldEventRepository? = null,
    orphanParentPurger: OrphanParentPurger? = null,
    homeDir: Path? = null,
    coverImageStore: CoverImageStore? = null,
) : SqlSyncableRepository<BookSyncPayload, BookId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.BOOKS,
        clock = clock,
    ),
    BookIngestPort,
    BookRevisionTouch {
    /** Bridge handing internal collaborators access to protected substrate hooks. */
    private val syncBridge: BookSyncBridge =
        object : BookSyncBridge {
            override val domainName: String get() = this@BookRepository.domainName

            override fun nextRevision(): Long = this@BookRepository.nextRevision()

            override fun readPayloadInTransaction(idStr: String): BookSyncPayload? =
                this@BookRepository.readPayload(idStr)

            override suspend fun findById(id: BookId): BookSyncPayload? = this@BookRepository.findById(id)

            override suspend fun findAllByIds(bookIds: List<String>): List<BookSyncPayload> =
                this@BookRepository.findAllByIds(bookIds)

            override suspend fun upsert(
                value: BookSyncPayload,
                clientOpId: String?,
            ): AppResult<BookSyncPayload> = this@BookRepository.upsert(value, clientOpId = clientOpId)

            override fun TransactionWithReturn<*>.upsertBookInOpenTransaction(
                value: BookSyncPayload,
                suppressed: Boolean,
                clientOpId: String?,
            ): BookSyncPayload =
                with(this@BookRepository) {
                    this@upsertBookInOpenTransaction.upsertInOpenTransaction(value, suppressed, clientOpId)
                }

            override fun TransactionWithReturn<*>.emitBookUpdatedAfterCommit(
                idStr: String,
                rev: Long,
                now: Long,
                capture: FrameCapture?,
            ) {
                with(this@BookRepository) {
                    this@emitBookUpdatedAfterCommit.publishUpdatedAfterCommit(
                        idStr = idStr,
                        rev = rev,
                        now = now,
                        capture = capture,
                    )
                }
            }

            override suspend fun softDeleteRow(
                id: BookId,
                clientOpId: String?,
                userId: String?,
            ): AppResult<Unit> = super@BookRepository.softDelete(id, clientOpId, userId)
        }

    /** Cover file and path helpers — file I/O and path resolution outside the sync seam. */
    private val managedCoverFiles = ManagedCoverFiles(coverImageStore, homeDir, db)

    /** Cover column resolution and targeted cover-row writes. */
    private val coverWriter = BookCoverWriter(db, clock, syncBridge)

    /** Read query helpers — path/inode lookup and contributor/series joins. */
    private val bookFinder = BookFinder(db)

    /** Genre junction write helpers (SQLDelight) — `book_genres` and auto-create (no revision/bus). */
    private val bookGenreWriter = BookGenreWriter(db, clock, GenreAutoCreator(genreRepository))

    /** Book-row and child-table write mechanics (transaction-scoped, no revision/bus calls). */
    private val bookAggregateWriter =
        BookAggregateWriter(
            db = db,
            bookGenreWriter = bookGenreWriter,
            coverWriter = coverWriter,
            writeSystemMembership = ::writeSystemMembership,
        )

    /** Tag junction write helper — add-only on rescan, null when tags domain is not wired. */
    private val bookTagWriter =
        if (tagRepository != null && bookTagRepository != null) {
            BookTagWriter(clock, tagRepository, bookTagRepository)
        } else {
            null
        }

    /** Path resolution, inode move detection, and organizer DB move operations. */
    private val locationManager =
        BookLocationManager(db = db, clock = clock, bookFinder = bookFinder, sync = syncBridge)

    /** Absence sweeps, soft-delete cascades, folder queries, and tombstone revival. */
    private val lifecycle =
        BookLifecycle(
            db = db,
            clock = clock,
            sync = syncBridge,
            locationManager = locationManager,
            bookTagRepository = bookTagRepository,
            bookMoodRepository = bookMoodRepository,
            collectionBookRepository = collectionBookRepository,
            entityRepository = entityRepository,
            worldEventRepository = worldEventRepository,
            orphanParentPurger = orphanParentPurger,
        )

    /** Single-book and batched scanner ingest orchestration. */
    private val scanIngestor =
        BookScanIngestor(
            db = db,
            sync = syncBridge,
            analyzedBookMapper = analyzedBookMapper,
            contributorRepository = contributorRepository,
            seriesRepository = seriesRepository,
            bookFinder = bookFinder,
            bookGenreWriter = bookGenreWriter,
            bookTagWriter = bookTagWriter,
            managedCoverFiles = managedCoverFiles,
            locationManager = locationManager,
            lifecycle = lifecycle,
        )

    override fun idAsString(id: BookId): String = id.value

    override val BookSyncPayload.id: BookId
        get() = BookId(this.id)

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.booksQueries].
     * Mirrors the canonical Tag/Contributor shape.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.booksQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long {
                db.booksQueries.softDeleteById(
                    revision = revision,
                    updated_at = updatedAt,
                    deleted_at = deletedAt,
                    client_op_id = clientOpId,
                    id = id,
                )
                // The `Books.sq` softDeleteById is a plain UPDATE (no rows-affected return), so
                // unlike the Tag/Contributor template's `.value` we read SQLite changes()
                // directly — per-connection, reflects the immediately-preceding statement in
                // this same transaction.
                return db.booksQueries.changes().executeAsOne()
            }

            override fun selectIdsAboveRevision(
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.booksQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.booksQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    /**
     * Reads the book aggregate by id — joins child tables for contributors,
     * series, chapters, genres, and audio files. Returns null when the book row
     * is absent. Bound to the open SQLDelight transaction opened by the substrate.
     */
    override fun readPayload(idStr: String): BookSyncPayload? = db.readBookPayloads(listOf(idStr)).firstOrNull()

    /** Batched hydration: fetches each child table once per id-chunk and assembles in input-id order. */
    override fun readPayloads(idStrs: List<String>): List<BookSyncPayload> = db.readBookPayloads(idStrs)

    /** Tombstone projection — see [SqlSyncableRepository.minimizeTombstone]. */
    override fun minimizeTombstone(payload: BookSyncPayload): BookSyncPayload =
        payload.copy(
            // libraryId/folderId are @JvmInline value classes with isNotBlank() init guards
            // AND opaque UUIDs, not content — they stay. Everything content-bearing goes.
            title = "",
            sortTitle = null,
            subtitle = null,
            description = null,
            publishYear = null,
            normalizationGainDb = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            explicit = false,
            hasScanWarning = false,
            totalDuration = 0L,
            cover = null,
            rootRelPath = "",
            inode = null,
            scannedAt = 0L,
            contributors = emptyList(),
            series = emptyList(),
            genres = emptyList(),
            audioFiles = emptyList(),
            documents = emptyList(),
            chapters = emptyList(),
            chapterSource = ChapterSource.EMBEDDED,
            externalRefs = emptyList(),
            releaseDate = null,
            lastMatch = null,
        )

    /**
     * Applies the identity invariants ([withReconciledIdentity]) and writes the full book
     * aggregate inside the caller's open SQLDelight transaction.
     */
    override fun writePayload(
        value: BookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        bookAggregateWriter.writePayload(
            value = value,
            rev = rev,
            now = now,
            clientOpId = clientOpId,
            existed = existed,
        )
    }

    // --- Identity resolution & scan ingest -----------------------------------

    /**
     * Resolves an [AnalyzedBook] from the scanner to a stable [BookId] and writes
     * its aggregate, using the three-key identity model (spec §5.1):
     *
     *  1. **Natural key** `(folder_id, root_rel_path)` — a hit means a plain rescan.
     *  2. **Move-detection hint** `(folder_id, inode)` — checked only when the natural-key
     *     lookup misses; a hit preserves the UUID and updates `root_rel_path`.
     *  3. **No match** — a fresh UUID.
     */
    override suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
        systemCollectionId: String?,
        contributorIds: Map<String, ContributorId>?,
        seriesIds: Map<String, SeriesId>?,
    ): AppResult<IngestOutcome> =
        scanIngestor.resolveOrInsert(
            libraryId = libraryId,
            folderId = folderId,
            analyzed = analyzed,
            pendingCover = pendingCover,
            systemCollectionId = systemCollectionId,
            contributorIds = contributorIds,
            seriesIds = seriesIds,
        )

    override suspend fun resolveScanIdentities(books: Collection<AnalyzedBook>): ScanIdentityMaps =
        scanIngestor.resolveScanIdentities(books)

    override suspend fun resolveOrInsertAll(
        libraryId: LibraryId,
        folderId: FolderId,
        books: List<AnalyzedBook>,
        coversByBook: Map<String, PendingCover>,
        systemCollectionId: String?,
        identityMaps: ScanIdentityMaps,
        onProgress: suspend (processed: Int, failed: Int) -> Unit,
    ): PersistResult =
        scanIngestor.resolveOrInsertAll(
            libraryId = libraryId,
            folderId = folderId,
            books = books,
            coversByBook = coversByBook,
            systemCollectionId = systemCollectionId,
            identityMaps = identityMaps,
            onProgress = onProgress,
        )

    /** Builds a [BookSyncPayload] from [analyzed] under [bookId] and upserts the full aggregate. */
    suspend fun upsertFromAnalyzed(
        bookId: BookId,
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover? = null,
        systemCollectionId: String? = null,
        contributorIds: Map<String, ContributorId>? = null,
        seriesIds: Map<String, SeriesId>? = null,
    ): AppResult<BookSyncPayload> =
        scanIngestor.upsertFromAnalyzed(
            bookId = bookId,
            libraryId = libraryId,
            folderId = folderId,
            analyzed = analyzed,
            pendingCover = pendingCover,
            systemCollectionId = systemCollectionId,
            contributorIds = contributorIds,
            seriesIds = seriesIds,
        )

    // --- Soft-delete, absence sweeps & revival lifecycle ---------------------

    override suspend fun softDelete(
        id: BookId,
        clientOpId: String?,
        userId: String?,
    ): AppResult<Unit> = lifecycle.softDelete(id, clientOpId, userId)

    /**
     * Tombstones every non-deleted book in [libraryId] that a completed FULL scan did not
     * see — the book's directory was deleted or moved out of the library tree (spec §5.4).
     *
     * **Full-scan only.** Incremental scans must never call this method.
     */
    suspend fun softDeleteAbsent(
        libraryId: LibraryId,
        seenIds: Set<BookId>,
    ) {
        lifecycle.softDeleteAbsent(libraryId, seenIds)
    }

    override suspend fun softDeleteAbsentByPaths(
        libraryId: LibraryId,
        seen: Set<FolderScopedPath>,
        walkedIdentityFiles: Map<FolderScopedInode, String>,
    ): Int = lifecycle.softDeleteAbsentByPaths(libraryId, seen, walkedIdentityFiles)

    override suspend fun softDeleteByPath(
        folderId: FolderId,
        rootRelPath: String,
    ): Int = lifecycle.softDeleteByPath(folderId, rootRelPath)

    /** All book ids under [folderId] (tombstone-inclusive). */
    suspend fun idsByFolder(folderId: FolderId): List<BookId> = lifecycle.idsByFolder(folderId)

    /** Ids of books under [folderId] tombstoned at or after [deletedAtFloor]. */
    suspend fun idsByFolderDeletedSince(
        folderId: FolderId,
        deletedAtFloor: Long,
    ): List<BookId> = lifecycle.idsByFolderDeletedSince(folderId, deletedAtFloor)

    /** Revives a tombstoned book: clears `deleted_at`, bumps revision, and emits [SyncEvent.Updated]. */
    suspend fun reviveById(id: BookId): AppResult<Unit> = lifecycle.reviveById(id)

    /** Batch-revives every book in [ids] and cascades revival to its junctions and Story World entities/events. */
    suspend fun reviveByIds(
        ids: List<BookId>,
        cascadeFloor: Long,
    ) {
        lifecycle.reviveByIds(ids, cascadeFloor)
    }

    // --- Genres, metadata matching & aggregate reads -------------------------

    /** Replaces [bookId]'s genres with [rawGenres] resolved through the scanner's 3-step cascade. */
    suspend fun setBookGenres(
        bookId: BookId,
        rawGenres: List<String>,
    ): AppResult<Unit> = bookGenreWriter.setBookGenres(bookId, rawGenres)

    /** Resolves one raw genre string to its genre ids without writing any junction row. */
    internal suspend fun resolveGenreIds(raw: String): List<String> = bookGenreWriter.resolveGenreIds(raw)

    /** A revision taken from the global counter inside an open transaction. */
    internal fun allocateRevision(): Long = nextRevision()

    /** [id]'s aggregate, read inside an open transaction; null when the book is absent. */
    internal fun readPayloadInTransaction(id: String): BookSyncPayload? = readPayload(id)

    /** [id]'s stored revision, read inside an open transaction; null when the book is absent. */
    internal fun revisionInTransaction(id: String): Long? = db.booksQueries.selectRevisionById(id).executeAsOneOrNull()

    /**
     * Writes a metadata match (or its undo) to [value]'s book inside the caller's open transaction, at
     * [revision]: cover columns when [cover] is given, genre junctions when [genreIds] is given,
     * [ladderRungIds] linked on top, chapter source, then the aggregate itself.
     */
    internal fun writeMatchInTransaction(
        tx: TransactionWithReturn<*>,
        value: BookSyncPayload,
        cover: MatchCoverColumns?,
        genreIds: List<String>?,
        ladderRungIds: List<String>,
        revision: Long,
        suppressed: Boolean,
        capture: FrameCapture?,
    ): BookSyncPayload {
        if (cover != null) {
            db.booksQueries.setCoverColumns(
                cover_source = cover.source?.run { name.lowercase() },
                cover_path = cover.path,
                cover_hash = cover.hash,
                id = value.id,
            )
        }
        genreIds?.let { bookGenreWriter.writeJunctions(value.id, it) }
        ladderRungIds.forEach { db.bookGenresQueries.insertIfAbsent(book_id = value.id, genre_id = it) }
        // The payload's chapter source is the one to keep: set it first, so the sticky-user-chapters guard
        // in writePayload lets an undo restore chapters a match had renamed.
        db.booksQueries.updateChapterSource(value.chapterSource.name.lowercase(), value.id)
        val (saved, event) = tx.upsertEventInOpenTransaction(value, suppressed, revision = revision)
        tx.captureAfterCommit(capture, event)
        return saved
    }

    /** Reads the full book aggregate for [id], or null when absent. */
    suspend fun findById(id: BookId): BookSyncPayload? = suspendTransaction(db) { readPayload(id.value) }

    /** Batch-reads the full book aggregates for [bookIds] in input order, skipping absent rows. */
    suspend fun findAllByIds(bookIds: List<String>): List<BookSyncPayload> =
        suspendTransaction(db) { readPayloads(bookIds) }

    // --- Covers, tier labels, revisions & path moves -------------------------

    /** Sets the managed-cover columns (and optional `COVER` provenance) and bumps revision. */
    suspend fun setManagedCover(
        id: BookId,
        relPath: String,
        hash: String,
        source: CoverSource,
        provenance: FieldProvenance? = null,
    ): AppResult<Unit> =
        coverWriter.setManagedCover(
            id = id,
            relPath = relPath,
            hash = hash,
            source = source,
            provenance = provenance,
        )

    /**
     * Writes the book's two chapter-grouping tier names and bumps the row's revision so the change
     * propagates to clients. Opens its own transaction; publishes `SyncEvent.Updated` after commit.
     */
    suspend fun setTierLabels(
        id: BookId,
        bookTierLabel: String?,
        partTierLabel: String?,
    ): AppResult<Unit> {
        val idStr = idAsString(id)
        val capture = currentCoroutineContext()[FrameCapture.Key]
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.updateTierLabels(
                book_tier_label = bookTierLabel,
                part_tier_label = partTierLabel,
                revision = rev,
                updated_at = now,
                id = idStr,
            )
            if (db.booksQueries.changes().executeAsOne() == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = idStr))
            } else {
                publishUpdatedAfterCommit(idStr = idStr, rev = rev, now = now, capture = capture)
                AppResult.Success(Unit)
            }
        }
    }

    /** Nulls the managed-cover columns and bumps the row's revision so the change propagates. */
    suspend fun clearManagedCover(id: BookId): AppResult<Unit> = coverWriter.clearManagedCover(id)

    override suspend fun touchRevision(id: BookId): AppResult<Unit> {
        val idStr = idAsString(id)
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.touchRevision(revision = rev, updated_at = now, id = idStr)
            if (db.booksQueries.changes().executeAsOne() == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = idStr))
            } else {
                publishUpdatedAfterCommit(idStr, rev, now)
                AppResult.Success(Unit)
            }
        }
    }

    /** Rewrites [id]'s `root_rel_path` (and optional single-file audio filename) after a move on disk. */
    suspend fun moveRootRelPath(
        id: BookId,
        newRootRelPath: String,
        renamedAudioFilename: String? = null,
    ): AppResult<Unit> = locationManager.moveRootRelPath(id, newRootRelPath, renamedAudioFilename)

    override suspend fun touchRevisions(ids: List<BookId>): AppResult<Unit> {
        if (ids.isEmpty()) return AppResult.Success(Unit)
        return suspendTransaction(db) {
            for (id in ids) {
                val idStr = idAsString(id)
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                db.booksQueries.touchRevision(revision = rev, updated_at = now, id = idStr)
                if (db.booksQueries.changes().executeAsOne() != 0L) {
                    publishUpdatedAfterCommit(idStr, rev, now)
                }
            }
            AppResult.Success(Unit)
        }
    }

    /** Resolves where [id]'s cover image lives for the cover-serving route, or null when absent. */
    suspend fun coverInfo(id: BookId): CoverInfo? = managedCoverFiles.coverInfo(id)

    /** Every live book that has a cover, paired with its content hash. */
    suspend fun listLiveCovers(): List<LiveCover> = coverWriter.listLiveCovers()

    /**
     * Writes the `(systemCollectionId, bookId)` membership into `collection_books` inside the
     * open SQLDelight book transaction, atomically with the book insert (pure-union model).
     */
    private fun writeSystemMembership(
        systemCollectionId: String,
        bookId: String,
        now: Long,
    ) {
        val repo =
            requireNotNull(collectionBookRepository) {
                "system-collection membership requested but CollectionBookRepository is not wired"
            }
        if (db.collectionBooksQueries.existsByCollectionAndBook(systemCollectionId, bookId).executeAsOne()) {
            return
        }
        val syntheticId = Uuid.random().toString()
        val membershipRev = nextRevision()
        db.collectionBooksQueries.insertMembership(
            id = syntheticId,
            collection_id = systemCollectionId,
            book_id = bookId,
            created_at = now,
            updated_at = now,
            revision = membershipRev,
            client_op_id = null,
        )
        val event =
            SyncEvent.Created(
                id = syntheticId,
                revision = membershipRev,
                occurredAt = now,
                clientOpId = null,
                payload =
                    CollectionBookSyncPayload(
                        id = syntheticId,
                        collectionId = systemCollectionId,
                        bookId = bookId,
                        createdAt = now,
                        revision = membershipRev,
                        deletedAt = null,
                    ),
            )
        db.transaction {
            emitInPublishOrder(bus = bus, repo = repo, event = event, userId = null)
        }
    }

    /**
     * Reads the saved aggregate and registers a post-commit `SyncEvent.Updated` emit carrying it.
     * Must run inside the open SQLDelight transaction.
     */
    private fun TransactionWithReturn<*>.publishUpdatedAfterCommit(
        idStr: String,
        rev: Long,
        now: Long,
        capture: FrameCapture? = null,
    ) {
        val saved =
            readPayload(idStr)
                ?: error("readPayload returned null immediately after a cover/touch write for $idStr")
        val event = SyncEvent.Updated(id = idStr, revision = rev, occurredAt = now, clientOpId = null, payload = saved)
        emitAfterCommit(event = event, userId = null)
        if (capture != null) {
            afterCommit { capture.add(toSyncFrame(event)) }
        }
    }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: BookId): String = idAsString(id)

    /** Test-only accessor for the protected [readPayload]. */
    internal suspend fun readPayloadForTest(idStr: String): BookSyncPayload? =
        suspendTransaction(db) { readPayload(idStr) }

    /** Test-only accessor for the protected [readPayloads]. */
    internal suspend fun readPayloadsForTest(idStrs: List<String>): List<BookSyncPayload> =
        suspendTransaction(db) { readPayloads(idStrs) }
}
