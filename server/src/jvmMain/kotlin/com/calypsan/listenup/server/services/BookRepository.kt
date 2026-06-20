package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverInfo
import com.calypsan.listenup.server.cover.ManagedCoverFiles
import com.calypsan.listenup.server.cover.StoredCoverInfo
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction as exposedSuspendTransaction

private val log = KotlinLogging.logger {}

/**
 * Server-side repository for the books aggregate, over SQLDelight.
 *
 * Extends the [SqlSyncableRepository] substrate, owning the multi-table read/write
 * for a book + its contributors + series + chapters + audio files. The substrate
 * orchestrates revision bumping and change-bus publication; this class
 * implements [readPayload] and [writePayload] to manage the aggregate shape.
 *
 * `idAsString(BookId) = id.value` is load-bearing — the substrate's default
 * `toString()` on a value class returns `"BookId(value=foo)"`, which would
 * corrupt every column the id is written to.
 *
 * **Two database handles during the cutover.** [db] (the SQLDelight [ListenUpDatabase])
 * backs every book read/write and the FTS index. [exposedDb] (the Exposed [Database] over
 * the same migrated file) backs three collaborators that are NOT yet converted:
 *  - [ManagedCoverFiles] / [coverInfo] — read `library_folders` (out of this unit's `.sq` set),
 *  - [bookGenreWriter] — the Exposed `book_genres`/`genres` writer (genre CRUD stays Exposed),
 *  - the access-filtered [pullSince] / [digest] / [searchFts] id reads — runtime-built
 *    [SqlFragment] subqueries with Exposed-typed args, which no static SQLDelight query can
 *    splice. These are READS over the shared WAL file, so they coexist with the SQLDelight
 *    writer safely.
 *
 * Genre writes run as a **separate, sequential** Exposed transaction after the book write commits
 * (see [upsertFromAnalyzed]) — never nested inside the SQLDelight book transaction — so a
 * mixed-engine writer never contends for the single SQLite write lock.
 *
 * The inbox-quarantine membership is the one exception: it is written ATOMICALLY inside the
 * SQLDelight book transaction ([writeInboxMembership]), because a separate post-commit write left
 * a narrow REST-catch-up window where a member could pull the briefly-uncollected (public) book.
 * That write uses the same SQLDelight engine as the book row, so it never nests an Exposed write
 * inside the SQLDelight transaction; the Exposed [CollectionBookRepository] stays canonical for
 * every other `collection_books` operation.
 *
 * @param contributorRepository the syncable contributors catalogue;
 *   [upsertFromAnalyzed] resolves each author/narrator name through it to a
 *   stable [ContributorId] before the aggregate write.
 * @param seriesRepository the syncable series catalogue;
 *   [upsertFromAnalyzed] resolves each series name through it before the
 *   aggregate write.
 * @param genreRepository the syncable genres catalogue; backs the
 *   [GenreAutoCreator] that the book-genre writer uses to auto-create a flat
 *   live genre for any scanner string the alias/normalizer cascade can't resolve.
 * @param tagRepository the syncable tags catalogue; backs the [BookTagWriter]
 *   that persists the ABS `metadata.json` `tags[]` array at scan, auto-creating
 *   tag rows for trope names that don't yet exist.
 * @param bookTagRepository the syncable `book_tags` junction; the [BookTagWriter]
 *   links the book to each scanned tag through it (add-only on rescan), and the
 *   book soft-delete cascades through it.
 */
class BookRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    private val exposedDb: Database,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val genreRepository: GenreRepository,
    private val analyzedBookMapper: AnalyzedBookMapper = AnalyzedBookMapper(),
    clock: Clock = Clock.System,
    private val collectionBookRepository: com.calypsan.listenup.server.sync.CollectionBookRepository? = null,
    private val tagRepository: com.calypsan.listenup.server.sync.TagRepository? = null,
    private val bookTagRepository: com.calypsan.listenup.server.sync.BookTagRepository? = null,
    private val homeDir: Path? = null,
    private val coverImageStore: CoverImageStore? = null,
) : SqlSyncableRepository<BookSyncPayload, BookId>(
        db = db,
        bus = bus,
        registry = registry,
        domainName = "books",
        clock = clock,
    ),
    BookIngestPort,
    BookRevisionTouch {
    /** Cover file and path helpers — file I/O and path resolution outside the sync seam (Exposed reads). */
    private val managedCoverFiles = ManagedCoverFiles(coverImageStore, homeDir, exposedDb)

    /** Book-row child-table write mechanics (transaction-scoped, no revision/bus calls). */
    private val bookAggregateWriter = BookAggregateWriter(db)

    /** Read query helpers — FTS, path/inode lookup, and contributor/series joins. */
    private val bookFinder = BookFinder(db, exposedDb)

    /** Genre junction write helpers (Exposed) — `book_genres` and auto-create (no revision/bus). */
    private val bookGenreWriter = BookGenreWriter(exposedDb, clock, GenreAutoCreator(genreRepository))

    /**
     * Tag junction write helper — persists scanned ABS `tags[]` to `book_tags`,
     * auto-creating tag rows, add-only on rescan. Null when the tag repos aren't
     * wired (a books slice without the tags domain), in which case scan tags are
     * silently skipped.
     */
    private val bookTagWriter =
        if (tagRepository != null && bookTagRepository != null) {
            BookTagWriter(clock, tagRepository, bookTagRepository)
        } else {
            null
        }

    override val elementSerializer: KSerializer<BookSyncPayload> = BookSyncPayload.serializer()

    override fun idAsString(id: BookId): String = id.value

    override val BookSyncPayload.id: BookId
        get() = BookId(this.id)

    override fun BookSyncPayload.revisionOf(): Long = revision

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
     * is absent. Bound to the open SQLDelight transaction opened by the substrate's
     * `upsert` / `pullSince` / etc.
     */
    override fun readPayload(idStr: String): BookSyncPayload? = db.readBookPayloads(listOf(idStr)).firstOrNull()

    /** Batched hydration: fetches each child table once per id-chunk and assembles in input-id order. */
    override fun readPayloads(idStrs: List<String>): List<BookSyncPayload> = db.readBookPayloads(idStrs)

    /**
     * Writes the full book aggregate inside the substrate's open SQLDelight transaction.
     *
     * **Atomicity is the contract.** The root row, the four child tables (contributors,
     * series, chapters, audio files), the FTS index (`book_search` + `book_search_map`), and —
     * for a genuinely-new book the scan path marked for quarantine — the book→inbox
     * `collection_books` membership ([writeInboxMembership]) land together or not at all. The
     * substrate has already opened the transaction and resolved [existed]; this method issues
     * writes that bind to that transaction.
     *
     * Genre writes do NOT happen here — they run as a separate, sequential Exposed transaction
     * after this one commits (see [upsertFromAnalyzed]) so the not-yet-converted Exposed genre
     * writer never nests inside the SQLDelight write lock. The inbox membership, by contrast, is
     * a SQLDelight write on the same engine, so it joins this transaction safely and atomically.
     */
    override fun writePayload(
        value: BookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        // Read per-call extras the scan/edit paths installed via the coroutine context (mirrored
        // to a thread-local by BookWriteExtras's ThreadContextElement, so this non-suspend method
        // can read it inside the SQLDelight transaction). Null for all other callers.
        val extras = BookWriteExtras.current()
        val managedCover = extras?.managedCover

        if (existed) {
            // Sticky-upload merge: if the existing row carries a user-uploaded cover
            // (cover_source = 'uploaded'), preserve the cover columns so a re-scan does not
            // clobber an intentional user choice. Any other existing source (filesystem,
            // embedded, enriched) gets overwritten by the new scan data.
            val existingCoverSource =
                db.booksQueries
                    .selectCoverSourceById(value.id)
                    .executeAsOneOrNull()
                    ?.cover_source
            val isUploadedLocked = existingCoverSource == CoverSource.UPLOADED.name.lowercase()

            if (isUploadedLocked) {
                db.booksQueries.updateContentPreserveCover(
                    title = value.title,
                    sort_title = value.sortTitle,
                    subtitle = value.subtitle,
                    description = value.description,
                    publish_year = value.publishYear?.toLong(),
                    publisher = value.publisher,
                    language = value.language,
                    isbn = value.isbn,
                    asin = value.asin,
                    abridged = value.abridged.toDbLong(),
                    explicit = value.explicit.toDbLong(),
                    has_scan_warning = value.hasScanWarning.toDbLong(),
                    total_duration = value.totalDuration,
                    root_rel_path = value.rootRelPath,
                    inode = value.inode,
                    scanned_at = value.scannedAt,
                    revision = rev,
                    updated_at = now,
                    client_op_id = clientOpId,
                    id = value.id,
                )
            } else {
                val cover = resolveCoverColumns(value, managedCover)
                db.booksQueries.updateContent(
                    title = value.title,
                    sort_title = value.sortTitle,
                    subtitle = value.subtitle,
                    description = value.description,
                    publish_year = value.publishYear?.toLong(),
                    publisher = value.publisher,
                    language = value.language,
                    isbn = value.isbn,
                    asin = value.asin,
                    abridged = value.abridged.toDbLong(),
                    explicit = value.explicit.toDbLong(),
                    has_scan_warning = value.hasScanWarning.toDbLong(),
                    total_duration = value.totalDuration,
                    cover_source = cover.source,
                    cover_path = cover.path,
                    cover_hash = cover.hash,
                    root_rel_path = value.rootRelPath,
                    inode = value.inode,
                    scanned_at = value.scannedAt,
                    revision = rev,
                    updated_at = now,
                    client_op_id = clientOpId,
                    id = value.id,
                )
            }
            // Edit-path only: re-stamp the added date. `updateContent`/`updateContentPreserveCover`
            // never write created_at, so a rescan's placeholder value stays ignored — only an
            // explicit metadata edit (which sets this override) can move it.
            extras?.createdAtOverride?.let { db.booksQueries.updateCreatedAt(it, value.id) }
        } else {
            val cover = resolveCoverColumns(value, managedCover)
            db.booksQueries.insert(
                id = value.id,
                library_id = value.libraryId.value,
                folder_id = value.folderId.value,
                title = value.title,
                sort_title = value.sortTitle,
                subtitle = value.subtitle,
                description = value.description,
                publish_year = value.publishYear?.toLong(),
                publisher = value.publisher,
                language = value.language,
                isbn = value.isbn,
                asin = value.asin,
                abridged = value.abridged.toDbLong(),
                explicit = value.explicit.toDbLong(),
                has_scan_warning = value.hasScanWarning.toDbLong(),
                total_duration = value.totalDuration,
                cover_source = cover.source,
                cover_path = cover.path,
                cover_hash = cover.hash,
                root_rel_path = value.rootRelPath,
                inode = value.inode,
                scanned_at = value.scannedAt,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
            // Atomic inbox quarantine. When the scan path stashed an inbox id (genuinely-new book
            // in an inbox-enabled library), write the book→inbox `collection_books` membership in
            // THIS same SQLDelight transaction, so the book is collected the instant it exists.
            // The firehose's delivery-time access filter therefore never sees a momentarily-public
            // book, and no member can pull it as public in the REST catch-up window — the TOCTOU
            // the post-commit (Exposed) membership write left open is closed by atomicity. The
            // membership emits its own `collection_books` `SyncEvent.Created` so it propagates to
            // every device exactly as the Exposed path's emit did.
            extras?.inboxCollectionId?.let { inboxId -> writeInboxMembership(inboxId, value.id, now) }
        }

        bookAggregateWriter.replaceContributors(value.id, value.contributors)
        bookAggregateWriter.replaceSeries(value.id, value.series)
        bookAggregateWriter.replaceChapters(value.id, value.chapters)
        bookAggregateWriter.replaceAudioFiles(value.id, value.audioFiles)
        upsertFtsRow(value)
    }

    /**
     * The cover columns to write for [value] on an INSERT or non-sticky UPDATE.
     *
     * When [managedCover] is non-null, the scan-time managed cover lands in the same
     * statement (source + relPath + hash). Otherwise the source/hash come from the wire
     * payload's cover and the path is null — the managed path is set separately by
     * [setManagedCover]. Mirrors the prior `applyBookFields` cover branch exactly.
     */
    private fun resolveCoverColumns(
        value: BookSyncPayload,
        managedCover: StoredCoverInfo?,
    ): CoverColumns =
        if (managedCover != null) {
            CoverColumns(
                source = managedCover.source.name.lowercase(),
                path = managedCover.relPath,
                hash = managedCover.hash,
            )
        } else {
            CoverColumns(
                source =
                    value.cover
                        ?.source
                        ?.name
                        ?.lowercase(),
                path = null,
                hash = value.cover?.hash,
            )
        }

    private data class CoverColumns(
        val source: String?,
        val path: String?,
        val hash: String?,
    )

    // --- Identity resolution -------------------------------------------------

    /**
     * Resolves an [AnalyzedBook] from the scanner to a stable [BookId] and writes
     * its aggregate, using the three-key identity model (spec §5.1):
     *
     *  1. **Natural key** `(library_id, root_rel_path)` — a hit means a plain rescan.
     *  2. **Move-detection hint** `(library_id, inode)` — checked only when the natural-key
     *     lookup misses; a hit preserves the UUID and updates `root_rel_path`.
     *  3. **No match** — a fresh UUID.
     *
     * In every branch the write goes through [upsertFromAnalyzed]. Each lookup opens its
     * own short read transaction; the subsequent write opens its own. SQLite is single-writer,
     * so the consecutive transactions serialize cleanly with no lost-update race within a scan.
     */
    override suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
        inboxCollectionId: String?,
    ): AppResult<IngestOutcome> {
        val rootRelPath = analyzed.candidate.rootRelPath

        bookFinder.findByPath(libraryId, rootRelPath)?.let { existing ->
            return upsertFromAnalyzed(existing, libraryId, folderId, analyzed, pendingCover, inboxCollectionId)
                .map { IngestOutcome(existing, wasNew = false) }
        }

        analyzed.candidate.files
            .firstOrNull()
            ?.inode
            ?.let { inode ->
                bookFinder.findByInode(libraryId, inode)?.let { existing ->
                    val previousPath = findById(existing)?.rootRelPath
                    log.info { "Book moved: $previousPath → $rootRelPath" }
                    return upsertFromAnalyzed(existing, libraryId, folderId, analyzed, pendingCover, inboxCollectionId)
                        .map { IngestOutcome(existing, wasNew = false) }
                }
            }

        val newId = BookId(UUID.randomUUID().toString())
        return upsertFromAnalyzed(newId, libraryId, folderId, analyzed, pendingCover, inboxCollectionId)
            .map { IngestOutcome(newId, wasNew = true) }
    }

    /**
     * Tombstones this book and cascade-soft-deletes all of its `book_tags` junction
     * rows so clients receive per-row tombstones for the orphaned junctions.
     */
    override suspend fun softDelete(
        id: BookId,
        clientOpId: String?,
        userId: String?,
    ): AppResult<Unit> {
        val result = super.softDelete(id, clientOpId, userId)
        if (result is AppResult.Success) {
            bookTagRepository?.softDeleteAllForBook(id.value)
        }
        return result
    }

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
        val seenSet = seenIds.mapTo(mutableSetOf()) { it.value }
        val toDelete =
            suspendTransaction(db) {
                db.booksQueries
                    .selectLiveIdsAndPathsForLibrary(libraryId.value)
                    .executeAsList()
                    .map { it.id }
                    .filterNot { it in seenSet }
            }
        for (id in toDelete) {
            softDelete(BookId(id), clientOpId = null)
        }
    }

    /**
     * Tombstones every non-deleted book in [libraryId] whose `rootRelPath` is not
     * in [seenPaths] — the path-keyed counterpart to [softDeleteAbsent].
     *
     * **Full-scan only.** Same authoritativity contract as [softDeleteAbsent].
     */
    override suspend fun softDeleteAbsentByPaths(
        libraryId: LibraryId,
        seenPaths: Set<String>,
    ) {
        val toDelete =
            suspendTransaction(db) {
                db.booksQueries
                    .selectLiveIdsAndPathsForLibrary(libraryId.value)
                    .executeAsList()
                    .filterNot { it.root_rel_path in seenPaths }
                    .map { it.id }
            }
        for (id in toDelete) {
            softDelete(BookId(id), clientOpId = null)
        }
    }

    /**
     * Soft-deletes the live book at [rootRelPath] inside [libraryId], if one exists.
     *
     * Idempotent: a no-op when no live (non-deleted) book exists at that path.
     */
    override suspend fun softDeleteByPath(
        libraryId: LibraryId,
        rootRelPath: String,
    ) {
        val id =
            suspendTransaction<BookId?>(db) {
                db.booksQueries
                    .selectLiveIdByPath(libraryId.value, rootRelPath)
                    .executeAsOneOrNull()
                    ?.let { BookId(it) }
            } ?: return // already gone — idempotent no-op
        softDelete(id, clientOpId = null)
    }

    /**
     * Builds a [BookSyncPayload] from [analyzed] under the supplied [bookId] and
     * writes the full aggregate through the substrate's `upsert`.
     *
     * Genres and scan tags are reconciled in **separate, sequential** transactions after
     * the book write commits (genres via the Exposed [bookGenreWriter], tags via
     * [bookTagWriter]).
     *
     * The book→inbox membership for a genuinely-new book, by contrast, is written **atomically**
     * inside the book-insert transaction: when [inboxCollectionId] is non-null and this call
     * INSERTs a new book, the id rides in via [BookWriteExtras] and `writePayload` lands the
     * `collection_books` row in the same SQLDelight transaction (see [writeInboxMembership]). The
     * inbox id is stashed only when `existing == null`, so the UPDATE path of a rescan can never
     * re-inbox a released book. Atomicity closes the REST-catch-up window a post-commit membership
     * write would otherwise open — the book is never momentarily public.
     */
    suspend fun upsertFromAnalyzed(
        bookId: BookId,
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover? = null,
        inboxCollectionId: String? = null,
    ): AppResult<BookSyncPayload> {
        val resolvedContributors =
            analyzedBookMapper.buildContributors(analyzed).map { c ->
                c.copy(id = contributorRepository.resolveOrCreate(c.name, c.sortName).value)
            }
        val resolvedSeries =
            analyzedBookMapper.buildSeries(analyzed).map { s ->
                s.copy(id = seriesRepository.resolveOrCreate(s.name).value)
            }
        val payload =
            analyzedBookMapper.toBookSyncPayload(
                bookId = bookId,
                libraryId = libraryId,
                folderId = folderId,
                analyzed = analyzed,
                resolvedContributors = resolvedContributors,
                resolvedSeries = resolvedSeries,
            )
        // Read the existing aggregate ONCE — drives the idempotency check, the cover-source
        // sticky-UPLOADED skip, and the only-on-create inbox quarantine gate.
        val existing = findById(bookId)
        val isNew = existing == null
        val pendingCoverHash = pendingCover?.bytes?.sha256Hex()
        val coverUnchanged =
            existing?.cover?.source == CoverSource.UPLOADED ||
                pendingCoverHash == existing?.cover?.hash
        val result: AppResult<BookSyncPayload> =
            if (existing != null && coverUnchanged && payload.matchesStoredContent(existing)) {
                // Idempotent re-scan: content identical to what's stored — skip the
                // revision-bumping upsert AND the cover file-write.
                log.debug { "upsertFromAnalyzed: idempotent re-scan for ${bookId.value}, skipping revision bump" }
                AppResult.Success(existing)
            } else {
                // File I/O must stay OUTSIDE the DB transaction — store the cover first, then upsert.
                val storedCover =
                    if (existing?.cover?.source == CoverSource.UPLOADED) {
                        null // sticky: skip file write + preserve the uploaded cover in writePayload
                    } else {
                        managedCoverFiles.storeCoverIfPresent(bookId, pendingCover)
                    }
                // A genuinely-new book bound for the inbox has its `collection_books` membership
                // written ATOMICALLY inside writePayload's INSERT branch (see writeInboxMembership),
                // so the book is collected the instant it exists. There is no momentarily-public
                // window, so the `book.Created` emit fires normally — the firehose's delivery-time
                // access filter correctly excludes the now-collected book from members. The inbox id
                // rides in via BookWriteExtras; it is stashed only for a genuinely-new book (`isNew`),
                // never on the UPDATE path, so a rescan can't re-inbox a released book.
                withContext(
                    BookWriteExtras(
                        managedCover = storedCover,
                        inboxCollectionId = if (isNew) inboxCollectionId else null,
                    ),
                ) {
                    upsert(payload, clientOpId = null)
                }
            }
        if (result is AppResult.Success) {
            val now = clock.now().toEpochMilliseconds()
            // Genres: a separate, sequential Exposed transaction (idempotent, no revision bump).
            exposedSuspendTransaction(exposedDb) { bookGenreWriter.processGenreStrings(bookId, analyzed.genres, now) }
            bookTagWriter?.writeScanTags(bookId, analyzed.tags)
        }
        return result
    }

    /**
     * True when [this] freshly-scanned payload matches the [stored] aggregate in every content field.
     *
     * Normalizes the scanned payload's placeholder server-assigned fields (revision/updatedAt/createdAt
     * = 0, scannedAt = now), its `null` cover, and its empty `genres` to the stored values before
     * comparing, so the result reflects only real content changes. Audio-file and chapter rows drop
     * their `id` (server-generated UUID at rest, `""` from the mapper) before comparing.
     */
    private fun BookSyncPayload.matchesStoredContent(stored: BookSyncPayload): Boolean {
        val normalized =
            copy(
                revision = stored.revision,
                updatedAt = stored.updatedAt,
                createdAt = stored.createdAt,
                scannedAt = stored.scannedAt,
                deletedAt = stored.deletedAt,
                cover = stored.cover,
                genres = stored.genres,
                audioFiles = audioFiles.map { it.copy(id = "") },
                chapters = chapters.map { it.copy(id = "") },
            )
        val storedNormalized =
            stored.copy(
                audioFiles = stored.audioFiles.map { it.copy(id = "") },
                chapters = stored.chapters.map { it.copy(id = "") },
            )
        return normalized == storedNormalized
    }

    /**
     * Replaces [bookId]'s genres with [rawGenres], resolved through the same 3-step cascade as the
     * scanner. Idempotent — wipes the prior `book_genres`/`pending_book_genres` first. Writes the
     * junction only; pair it with a book `upsert` (which re-reads the junction and emits) so the
     * change propagates.
     */
    suspend fun setBookGenres(
        bookId: BookId,
        rawGenres: List<String>,
    ): AppResult<Unit> = bookGenreWriter.setBookGenres(bookId, rawGenres)

    /**
     * Reads the full book aggregate for [id], or null when absent. Opens its own
     * read transaction — usable outside the substrate's orchestration.
     */
    suspend fun findById(id: BookId): BookSyncPayload? = suspendTransaction(db) { readPayload(id.value) }

    /**
     * Sets the managed-cover columns (provenance + relative path + sha256 hash) and bumps the
     * row's revision so the change propagates to clients via the sync bus.
     *
     * Opens its own transaction. The `SyncEvent.Updated` is published after the transaction
     * commits, carrying the full aggregate.
     */
    suspend fun setManagedCover(
        id: BookId,
        relPath: String,
        hash: String,
        source: CoverSource,
    ): AppResult<Unit> {
        val idStr = idAsString(id)
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.updateManagedCover(
                cover_source = source.name.lowercase(),
                cover_path = relPath,
                cover_hash = hash,
                revision = rev,
                updated_at = now,
                id = idStr,
            )
            if (db.booksQueries.changes().executeAsOne() == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = idStr))
            } else {
                publishUpdatedAfterCommit(idStr, rev, now)
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Nulls the managed-cover columns and bumps the row's revision so the change propagates.
     * Opens its own transaction.
     */
    suspend fun clearManagedCover(id: BookId): AppResult<Unit> {
        val idStr = idAsString(id)
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.clearManagedCover(revision = rev, updated_at = now, id = idStr)
            if (db.booksQueries.changes().executeAsOne() == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = idStr))
            } else {
                publishUpdatedAfterCommit(idStr, rev, now)
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Bumps the row's revision (and `updatedAt`) without touching any content column, so a
     * visibility-only change — collection membership add/remove — re-enters every member's
     * incremental pull. Opens its own transaction.
     */
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

    /**
     * Resolves where [id]'s cover image lives for the cover-serving route. Delegates to
     * [ManagedCoverFiles.coverInfo]. Returns null when the book is absent or has no cover.
     */
    suspend fun coverInfo(id: BookId): CoverInfo? = managedCoverFiles.coverInfo(id)

    /**
     * Runs an FTS5 full-text search against `book_search` and returns matching
     * [BookId]s in rank order (best match first), capped at [limit] results.
     *
     * When [accessFilter] is non-null only ids the viewer can reach survive. See
     * [BookFinder.searchFts] for the splicing contract.
     */
    suspend fun searchFts(
        query: String,
        limit: Int,
        accessFilter: SqlFragment? = null,
    ): List<BookId> = bookFinder.searchFts(query, limit, accessFilter)

    /**
     * Returns the full book aggregates for every book linked to [contributorId].
     *
     * Used by [com.calypsan.listenup.server.api.ContributorServiceImpl.listBooksByContributor].
     */
    suspend fun findByContributor(contributorId: ContributorId): List<BookSyncPayload> =
        bookFinder.findByContributor(contributorId)

    /**
     * Returns the full book aggregates for every book in [seriesId], in series-position order.
     *
     * Used by [com.calypsan.listenup.server.api.SeriesServiceImpl.listBooksBySeries].
     */
    suspend fun findBySeries(seriesId: SeriesId): List<BookSyncPayload> = bookFinder.findBySeries(seriesId)

    /**
     * Access-filtered catch-up pull (spec §collections-propagation). The unfiltered path
     * ([extraWhere] null) delegates to the base; the filtered path reads the `(id, revision)`
     * page over the Exposed connection (the runtime-built subquery's args are Exposed-typed)
     * and hydrates via the SQLDelight [readPayloads].
     */
    override suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment?,
    ): Page<BookSyncPayload> {
        if (extraWhere == null) return super.pullSince(userId, cursor, limit, extraWhere)
        val idsWithRev =
            selectIdsWithRevRaw(
                revisionPredicate = "revision > ?",
                revisionArg = cursor,
                extraWhere = extraWhere,
                orderAndLimit = "ORDER BY revision ASC LIMIT ?",
                trailingArgs = listOf(IntegerColumnType() to limit),
            )
        val items = suspendTransaction(db) { readPayloads(idsWithRev.map { it.first }) }
        return Page(
            items = items,
            nextCursor = idsWithRev.lastOrNull()?.second,
            hasMore = idsWithRev.size == limit,
        )
    }

    /**
     * Access-filtered drift digest. The unfiltered path delegates to the base; the filtered
     * path reads the `(id, revision)` slice over the Exposed connection and computes the
     * permanent-wire-contract SHA-256 digest identically to the base.
     */
    override suspend fun digest(
        userId: String?,
        cursor: Long,
        extraWhere: SqlFragment?,
    ): DomainDigest {
        if (extraWhere == null) return super.digest(userId, cursor, extraWhere)
        val rows =
            selectIdsWithRevRaw(
                revisionPredicate = "revision <= ?",
                revisionArg = cursor,
                extraWhere = extraWhere,
                orderAndLimit = "",
                trailingArgs = emptyList(),
            ).sortedBy { it.first }
        return if (rows.isEmpty()) {
            DomainDigest(cursor = cursor, count = 0, hash = "")
        } else {
            val md = MessageDigest.getInstance("SHA-256")
            val joined = rows.joinToString(separator = "\n") { (id, rev) -> "$id|$rev" } + "\n"
            val hex = md.digest(joined.toByteArray(Charsets.UTF_8)).toHexStringLower()
            DomainDigest(cursor = cursor, count = rows.size, hash = "sha256:$hex")
        }
    }

    /**
     * Raw-SQL `(id, revision)` read for the access-filtered ([extraWhere] non-null) path.
     *
     * Splices the access subquery as `id IN (<extraWhere.sql>)` and runs it on the Exposed
     * connection over the shared WAL file — a read, so it never contends with the SQLDelight
     * writer. **Argument order is load-bearing** and matches the `?` order exactly: the
     * [revisionArg] first, then the [extraWhere] args, then any [trailingArgs] (the LIMIT `?`).
     */
    private suspend fun selectIdsWithRevRaw(
        revisionPredicate: String,
        revisionArg: Long,
        extraWhere: SqlFragment,
        orderAndLimit: String,
        trailingArgs: List<Pair<IColumnType<*>, Any>>,
    ): List<Pair<String, Long>> =
        exposedSuspendTransaction(exposedDb) {
            val sql =
                buildString {
                    append("SELECT id, revision FROM books ")
                    append("WHERE $revisionPredicate AND id IN (${extraWhere.sql})")
                    if (orderAndLimit.isNotEmpty()) append(" $orderAndLimit")
                }
            val args =
                buildList {
                    add(LongColumnType() to revisionArg)
                    addAll(extraWhere.args)
                    addAll(trailingArgs)
                }
            val results = mutableListOf<Pair<String, Long>>()
            TransactionManager.current().exec(stmt = sql, args = args) { rs ->
                while (rs.next()) results += rs.getString(1) to rs.getLong(2)
            }
            results
        }

    // --- FTS write (inside the open SQLDelight transaction) -------------------

    /**
     * Replaces the FTS row for [payload] in `book_search`, allocating or reusing the integer
     * rowid via `book_search_map`.
     *
     * `book_search` is a `contentless_delete=1` FTS5 table, so an update is a plain
     * `DELETE FROM book_search WHERE rowid = ?` (generated `deleteFtsRow`) followed by a fresh
     * `INSERT` (generated `insertFtsRow`). The insert covers all 8 columns; `tags`/`genres` are
     * written EMPTY on this book-upsert path (the richer population happens in the reindexer),
     * preserving the prior write-time behaviour, which only wrote the first five columns.
     *
     * Mirrors the prior Exposed `upsertFtsRow` exactly: resolve-or-allocate the rowid, blind
     * DELETE (harmless when no row exists for the rowid), then INSERT — never a read-then-merge.
     */
    private fun upsertFtsRow(payload: BookSyncPayload) {
        val rowid = resolveOrAllocateFtsRowid(payload.id)
        val contributorNames = payload.contributors.joinToString(", ") { it.name }
        val seriesNames = payload.series.joinToString(", ") { it.name }
        db.bookSearchQueries.deleteFtsRow(rowid)
        db.bookSearchQueries.insertFtsRow(
            rowid = rowid,
            title = payload.title,
            subtitle = payload.subtitle ?: "",
            description = payload.description ?: "",
            contributor_names = contributorNames,
            series_names = seriesNames,
            // tags/genres are populated by the reindexer (U3b), not at book-upsert time. Pass
            // EMPTY strings (not null/omitted) to preserve the prior write-time behaviour.
            tags = "",
            genres = "",
        )
    }

    /**
     * Returns the existing FTS rowid for [bookId], or allocates `MAX(rowid)+1` and records the
     * mapping. The rowid is a SQLite INTEGER — `Long` in SQLDelight (it was `Int` in Exposed);
     * the boundary conversion is deliberate, and FTS rowids never approach the Int ceiling at
     * library scale, so the wider type is purely safer.
     */
    private fun resolveOrAllocateFtsRowid(bookId: String): Long {
        db.bookSearchQueries
            .selectRowidForBook(bookId)
            .executeAsOneOrNull()
            ?.let { return it }
        val nextRowid =
            (
                db.bookSearchQueries
                    .selectMaxRowid()
                    .executeAsOne()
                    .MAX ?: 0L
            ) + 1L
        db.bookSearchQueries.insertMap(book_id = bookId, rowid = nextRowid)
        return nextRowid
    }

    /**
     * Writes the `(inboxCollectionId, bookId)` membership into `collection_books` inside the
     * open SQLDelight book transaction, atomically with the book insert.
     *
     * Replicates the canonical syncable-write shape the Exposed [CollectionBookRepository] uses for
     * a new junction row: the synthetic `"$collectionId:$bookId"` id, the global revision bump via
     * the shared counter ([nextRevision]), and a post-commit `collection_books`
     * [SyncEvent.Created] emit so other devices learn of the membership exactly as before. The emit
     * is routed through [collectionBookRepository] (the `collection_books` domain's
     * [com.calypsan.listenup.server.sync.SyncableRepo]) — not this book repo — so the firehose
     * encodes it with the collection-book serializer and delivers it under the right domain.
     *
     * Idempotent: a pre-existing live or tombstoned row for the pair is left untouched (the
     * INSERT is skipped). For the genuinely-new-book INSERT path this guard is defensive — the
     * composite PK includes `book_id`, which cannot already exist — but it keeps the write safe
     * against any future caller that reaches this branch for a non-new book.
     *
     * **Must run inside the open SQLDelight transaction.** The emit is deferred to the
     * transaction's `afterCommit` (via a nested no-op transaction whose hook transfers to the
     * outermost commit), so it never races the firehose's delivery-time access read against an
     * uncommitted row — exactly the deferral semantics the base's own emits use.
     *
     * @throws IllegalArgumentException when an inbox id was stashed but no
     *   [CollectionBookRepository] is wired — a misconfiguration that would silently drop the
     *   quarantine, so it fails loudly rather than leaking a public book.
     */
    private fun writeInboxMembership(
        inboxCollectionId: String,
        bookId: String,
        now: Long,
    ) {
        val repo =
            requireNotNull(collectionBookRepository) {
                "inbox quarantine requested but CollectionBookRepository is not wired"
            }
        // Idempotency: never re-insert an existing pair (defensive on the new-book INSERT path).
        if (db.collectionBooksQueries.existsByCollectionAndBook(inboxCollectionId, bookId).executeAsOne()) {
            return
        }
        val syntheticId = "$inboxCollectionId:$bookId"
        val membershipRev = nextRevision()
        db.collectionBooksQueries.insertMembership(
            id = syntheticId,
            collection_id = inboxCollectionId,
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
                        collectionId = inboxCollectionId,
                        bookId = bookId,
                        createdAt = now,
                        revision = membershipRev,
                        deletedAt = null,
                    ),
            )
        // Defer the collection_books emit to the outermost transaction's after-commit, in publish
        // order. A nested transaction transfers its afterCommit hooks to the enclosing one, so this
        // fires once, after the book + membership rows are durably committed — the engine-native
        // equivalent of the base's deferEmit, but routed through the collection_books repo.
        db.transaction {
            afterCommit { bus.emit(repo = repo, event = event, userId = null) }
        }
    }

    /**
     * Reads the saved aggregate and registers a post-commit `SyncEvent.Updated` emit carrying it.
     * Shared by [setManagedCover] / [clearManagedCover] / [touchRevision]. Must run inside the
     * open SQLDelight transaction (the `afterCommit` hook fires when it commits).
     */
    private fun app.cash.sqldelight.TransactionWithReturn<*>.publishUpdatedAfterCommit(
        idStr: String,
        rev: Long,
        now: Long,
    ) {
        val saved =
            readPayload(idStr)
                ?: error("readPayload returned null immediately after a cover/touch write for $idStr")
        emitAfterCommit(
            event =
                SyncEvent.Updated(
                    id = idStr,
                    revision = rev,
                    occurredAt = now,
                    clientOpId = null,
                    payload = saved,
                ),
            userId = null,
        )
    }

    /**
     * Test-only accessor for the protected [idAsString].
     */
    internal fun idAsStringForTest(id: BookId): String = idAsString(id)

    /**
     * Test-only accessor for the protected [readPayload].
     */
    internal suspend fun readPayloadForTest(idStr: String): BookSyncPayload? =
        suspendTransaction(db) { readPayload(idStr) }

    /** Test-only accessor for the protected [readPayloads]. */
    internal suspend fun readPayloadsForTest(idStrs: List<String>): List<BookSyncPayload> =
        suspendTransaction(db) { readPayloads(idStrs) }
}

/** Returns the SHA-256 hex digest of [this] byte array. */
private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { "%02x".format(it) }
}

/** Lowercase hex of [this] digest bytes — matches the base digest format. */
private fun ByteArray.toHexStringLower(): String = joinToString("") { "%02x".format(it) }

/** Maps a wire `Boolean` to the SQLite `0/1` INTEGER the books table stores. */
private fun Boolean.toDbLong(): Long = if (this) 1L else 0L
