package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.SidecarCuration
import com.calypsan.listenup.api.dto.scanner.SidecarCurationChapter
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverInfo
import com.calypsan.listenup.server.cover.ManagedCoverFiles
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.cover.StoredCoverInfo
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.TransactionLocal
import com.calypsan.listenup.server.db.sqldelight.setTransactionLocal
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.io.files.Path
import kotlin.uuid.Uuid
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

private val log = loggerFor<BookRepository>()

/**
 * Books per chunked write transaction in [BookRepository.resolveOrInsertAll]. Each chunk is one
 * [com.calypsan.listenup.server.db.sqldelight.suspendTransaction] commit, so a 1100-book scan is ~5
 * write transactions instead of 1100. Sized to keep a single transaction's write set bounded (a
 * runaway transaction would hold the write lock and grow the WAL) while still amortizing commit cost.
 */
private const val PERSIST_CHUNK_SIZE = 250

/**
 * A book resolved + prepared by [BookRepository.resolveOrInsertAll]'s suspend prepare phase, ready for
 * the synchronous chunked write loop. [payload] is the built aggregate; [extras] carries the
 * pre-resolved managed cover, system-collection id (on genuine insert), and genre ids the synchronous
 * `writePayload` reads via the transaction-local; [skip] marks an idempotent re-scan whose content +
 * cover are unchanged (no write, no revision bump, no emit).
 */
private class PreparedBook(
    val bookId: BookId,
    val payload: BookSyncPayload,
    val skip: Boolean,
    val genreIds: List<String>,
    val tags: List<String>,
    val extras: BookWriteExtras,
    /**
     * The `deleted_at` of the stored row this write is reviving, or null when the book is genuinely
     * new or already live. Non-null only when re-ingesting a TOMBSTONED book — the write clears its
     * `deleted_at`, so its cascade-tombstoned junctions must be revived (floored at this value).
     */
    val revivedFromDeletedAt: Long?,
)

/**
 * The output of [BookRepository]'s prepare phase: the [prepared] books ready for the chunked write
 * loop, plus [prepareFailed] — books rejected during prepare (an absolute rootRelPath) that the write
 * loop must count as failed up front.
 */
private class PreparedBatch(
    val prepared: List<PreparedBook>,
    val prepareFailed: Int,
) {
    operator fun component1() = prepared

    operator fun component2() = prepareFailed
}

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
 * **Database handles.** [db] (the SQLDelight [ListenUpDatabase]) backs every book read/write,
 * the genre junction writes, the FTS index, and the cover collaborator [ManagedCoverFiles].
 * [driver] (the shared SQLDelight [SqlDriver] behind [db]) runs the access-filtered [pullSince]
 * / [digest] id reads engine-neutrally — the runtime-built [SqlFragment] access subquery now
 * carries plain raw args, so it splices over the driver inside the same `suspendTransaction(db)`.
 * The access-filtered FTS read ([searchFts]) is likewise engine-neutral inside [BookFinder].
 *
 * Genre writes ([bookGenreWriter]) now run over SQLDelight too, as a **separate, sequential**
 * pass after the book write commits (see [upsertFromAnalyzed]) — never nested inside the
 * SQLDelight book transaction — so a single writer never contends for the SQLite write lock.
 *
 * The system-collection membership (pure-union model) is the one exception: a genuinely-new
 * book's `collection_books` row — ALL_BOOKS for a non-held library, INBOX for a held one — is
 * written ATOMICALLY inside the SQLDelight book transaction ([writeSystemMembership]), because a
 * separate post-commit write left a narrow REST-catch-up window where a member could pull a held
 * book before its INBOX membership landed. That write uses the same SQLDelight engine as the book
 * row, so it never nests an Exposed write inside the SQLDelight transaction; the Exposed
 * [CollectionBookRepository] stays canonical for every other `collection_books` operation.
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
    override val driver: SqlDriver,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val genreRepository: GenreRepository,
    private val analyzedBookMapper: AnalyzedBookMapper = AnalyzedBookMapper(),
    clock: Clock = Clock.System,
    private val collectionBookRepository: com.calypsan.listenup.server.sync.CollectionBookRepository? = null,
    private val tagRepository: com.calypsan.listenup.server.sync.TagRepository? = null,
    private val bookTagRepository: com.calypsan.listenup.server.sync.BookTagRepository? = null,
    private val bookMoodRepository: com.calypsan.listenup.server.sync.BookMoodRepository? = null,
    private val orphanParentPurger: OrphanParentPurger? = null,
    private val homeDir: Path? = null,
    private val coverImageStore: CoverImageStore? = null,
) : SqlSyncableRepository<BookSyncPayload, BookId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.BOOKS,
        clock = clock,
    ),
    BookIngestPort,
    BookRevisionTouch {
    /** Cover file and path helpers — file I/O and path resolution outside the sync seam. */
    private val managedCoverFiles =
        ManagedCoverFiles(coverImageStore, homeDir, db)

    /** Book-row child-table write mechanics (transaction-scoped, no revision/bus calls). */
    private val bookAggregateWriter = BookAggregateWriter(db)

    /** FTS index write mechanics (transaction-scoped, no revision/bus calls). */
    private val bookFtsWriter = BookFtsWriter(db)

    /** Read query helpers — FTS, path/inode lookup, and contributor/series joins. */
    private val bookFinder = BookFinder(db, driver)

    /** Genre junction write helpers (SQLDelight) — `book_genres` and auto-create (no revision/bus). */
    private val bookGenreWriter = BookGenreWriter(db, clock, GenreAutoCreator(genreRepository))

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
     * is absent. Bound to the open SQLDelight transaction opened by the substrate's
     * `upsert` / `pullSince` / etc.
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
            userEditedFields = emptySet(),
        )

    /**
     * Writes the full book aggregate inside the substrate's open SQLDelight transaction.
     *
     * **Atomicity is the contract.** The root row, the four child tables (contributors,
     * series, chapters, audio files), the FTS index (`book_search` + `book_search_map`), and —
     * for a genuinely-new book the scan path assigned to a system collection — the book→collection
     * `collection_books` membership ([writeSystemMembership]) land together or not at all. The
     * substrate has already opened the transaction and resolved [existed]; this method issues
     * writes that bind to that transaction.
     *
     * Genre writes do NOT happen here — they run as a separate, sequential Exposed transaction
     * after this one commits (see [upsertFromAnalyzed]) so the not-yet-converted Exposed genre
     * writer never nests inside the SQLDelight write lock. The system-collection membership, by
     * contrast, is a SQLDelight write on the same engine, so it joins this transaction safely and
     * atomically.
     */
    override fun writePayload(
        value: BookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        // Read per-call extras the scan/edit paths installed via the coroutine context (carried by
        // a TransactionLocal and mirrored onto this thread by suspendTransaction, so this non-suspend
        // method can read it inside the SQLDelight transaction). Null for all other callers.
        val extras = BookWriteExtras.current()
        val managedCover = extras?.managedCover

        // Per-field user-edit provenance (rescan data-safety). The scan paths' merge has already
        // restored any protected scalar field (title/subtitle/description) onto `value` and folded the
        // union into `value.userEditedFields`; this serializes that set into the row. The collection
        // preserve flags ride in via the extras (the merged payload can't carry the incoming-vs-existing
        // distinction the skip-the-replace decision needs), defaulting false for every non-scan path.
        val userEditedFieldsColumn = value.userEditedFields.toUserEditedFieldsColumn()
        val preserveContributors = extras?.preserveContributors == true
        val preserveSeries = extras?.preserveSeries == true

        // Sticky-user-chapters merge: if the existing row carries a user-edited chapter set
        // (chapter_source = 'user'), preserve the chapter rows so a re-scan does not clobber
        // an intentional user edit. A subsequent USER edit (value.chapterSource == USER) is
        // still allowed through. On INSERT (existed == false) this is always false.
        val existingChapterSource =
            if (existed) {
                db.booksQueries
                    .selectChapterSourceById(value.id)
                    .executeAsOneOrNull()
            } else {
                null
            }
        val preserveChapters =
            existingChapterSource == ChapterSource.USER.name.lowercase() &&
                value.chapterSource != ChapterSource.USER

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
                    user_edited_fields = userEditedFieldsColumn,
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
                    user_edited_fields = userEditedFieldsColumn,
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
                user_edited_fields = userEditedFieldsColumn,
                root_rel_path = value.rootRelPath,
                inode = value.inode,
                scanned_at = value.scannedAt,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
            // Atomic system-collection membership (pure-union model). When the scan path stashed
            // a system collection id (genuinely-new book — ALL_BOOKS for a non-held library, INBOX for
            // a held one), write the book→collection `collection_books` membership in THIS same
            // SQLDelight transaction, so the book is collected the instant it exists. The two cases are
            // mutually exclusive and resolved upstream (BookPersister.resolveSystemCollectionId): a held
            // book joins INBOX only, never ALL_BOOKS, so it is never visible to members via the ALL_BOOKS
            // grant. The firehose's delivery-time access filter therefore never observes a held book as
            // accessible, and no member can pull it in the REST catch-up window — the TOCTOU a post-commit
            // membership write left open is closed by atomicity. The membership emits its own
            // `collection_books` `SyncEvent.Created` so it propagates to every device.
            extras?.systemCollectionId?.let { sysId -> writeSystemMembership(sysId, value.id, now) }
        }

        replaceBookChildren(value, preserveContributors, preserveSeries, preserveChapters)

        // Batched scan-persist path only: genre junctions ride INSIDE this transaction from the
        // pre-resolved ids the prepare phase built, collapsing a genred book to a single commit. The
        // single-book paths leave genreIds null and run the separate post-commit processGenreStrings
        // pass in upsertFromAnalyzed instead (no double write — the two are mutually exclusive).
        extras?.genreIds?.let { genreIds -> bookGenreWriter.writeJunctions(value.id, genreIds) }
    }

    /**
     * Replaces a book's child-collection rows after the parent upsert, honouring the per-field
     * user-edit provenance preserve flags: a `CONTRIBUTORS`/`SERIES`/chapters edit the stored book
     * carries (and this write isn't itself re-editing) keeps its rows — the collection analogue of the
     * scalar preserve in [mergeUserEdits]. Audio files, documents, and the FTS row always refresh.
     */
    private fun replaceBookChildren(
        value: BookSyncPayload,
        preserveContributors: Boolean,
        preserveSeries: Boolean,
        preserveChapters: Boolean,
    ) {
        if (!preserveContributors) bookAggregateWriter.replaceContributors(value.id, value.contributors)
        if (!preserveSeries) bookAggregateWriter.replaceSeries(value.id, value.series)
        if (!preserveChapters) {
            bookAggregateWriter.replaceChapters(value.id, value.chapters)
            db.booksQueries.updateChapterSource(value.chapterSource.name.lowercase(), value.id)
        }
        bookAggregateWriter.replaceAudioFiles(value.id, value.audioFiles)
        bookAggregateWriter.replaceDocuments(value.id, value.documents)
        bookFtsWriter.upsertFtsRow(value)
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
     *  1. **Natural key** `(folder_id, root_rel_path)` — a hit means a plain rescan.
     *  2. **Move-detection hint** `(folder_id, inode)` — checked only when the natural-key
     *     lookup misses; a hit preserves the UUID and updates `root_rel_path`.
     *  3. **No match** — a fresh UUID.
     *
     * In every branch the write goes through [upsertFromAnalyzed], which builds a
     * [BookSyncPayload] and hands it to the substrate's `upsert` — so revision bumping and
     * `SyncEvent` publication happen uniformly. Each lookup opens its own short read transaction;
     * the subsequent write opens its own. SQLite is single-writer, so the consecutive transactions
     * serialize cleanly with no lost-update race within a single scan pass.
     *
     * @return [AppResult.Success] carrying an [IngestOutcome] — the stable
     *   [BookId] (newly minted or pre-existing) plus a `wasNew` flag the scan
     *   coordinator uses to gate system-collection membership — only when the aggregate write
     *   landed. An [AppResult.Failure] means [upsertFromAnalyzed] did not persist
     *   the book; callers must not treat the failure as a persisted aggregate,
     *   and for a new book the minted UUID points at nothing.
     */
    override suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
        systemCollectionId: String?,
        contributorIds: Map<String, ContributorId>?,
        seriesIds: Map<String, SeriesId>?,
    ): AppResult<IngestOutcome> {
        val rootRelPath = analyzed.candidate.rootRelPath
        // Defense-in-depth: the natural key is library-relative. An absolute path here means an
        // upstream scanner bug leaked one (see FileHelpers.relativeTo / the 2026-06-25 regression);
        // persisting it would miss findByPath on every rescan and mass-trip the inode "moved" branch.
        // Fail loud and contained — BookPersister.persistOne logs and skips the offending book.
        require(!rootRelPath.startsWith("/")) {
            "rootRelPath must be library-relative, got absolute: $rootRelPath"
        }

        // The three identity branches differ only in the resolved BookId and the wasNew flag; the
        // aggregate write (and its threaded pre-resolved id maps) is identical, so close over it once.
        suspend fun write(bookId: BookId): AppResult<BookSyncPayload> =
            upsertFromAnalyzed(
                bookId,
                libraryId,
                folderId,
                analyzed,
                pendingCover,
                systemCollectionId,
                contributorIds,
                seriesIds,
            )

        bookFinder.findByPath(folderId, rootRelPath)?.let { existing ->
            return write(existing).map { IngestOutcome(existing, wasNew = false) }
        }

        analyzed.candidate
            .identityInode()
            ?.let { inode ->
                // The move hint is folder-scoped `(folder_id, inode)` — identity is anchored to the
                // owning folder. Tradeoff: moving a book directory BETWEEN two folders of the same
                // library re-mints its id (the new folder's inode lookup misses); an intra-folder move
                // preserves it. Accepted — cross-folder moves are rare, and folder-scoping is what keeps
                // two folders sharing a relative path (or an inode) from aliasing each other.
                bookFinder.findByInode(folderId, inode)?.let { existing ->
                    val previousPath = findById(existing)?.rootRelPath
                    log.info { "Book moved: $previousPath → $rootRelPath" }
                    return write(existing).map { IngestOutcome(existing, wasNew = false) }
                }
            }

        val newId = BookId(Uuid.random().toString())
        return write(newId).map { IngestOutcome(newId, wasNew = true) }
    }

    /**
     * Batch-resolves every contributor and series across [books] to a stable id in two bulk
     * lookups (one per catalogue), run ONCE before the persist loop. The identities are built with
     * the SAME [analyzedBookMapper] `buildContributors`/`buildSeries` that [upsertFromAnalyzed]
     * uses, so every key in the returned maps matches what the per-book lookup recomputes — no book
     * falls through to a per-call `resolveOrCreate`.
     */
    override suspend fun resolveScanIdentities(books: Collection<AnalyzedBook>): ScanIdentityMaps {
        val contributorIdentities =
            books.flatMap { analyzedBookMapper.buildContributors(it) }.map { it.name to it.sortName }
        val seriesNames = books.flatMap { analyzedBookMapper.buildSeries(it) }.map { it.name }
        return ScanIdentityMaps(
            contributors = contributorRepository.resolveOrCreateAll(contributorIdentities),
            series = seriesRepository.resolveOrCreateAll(seriesNames),
            genres = resolveScanGenres(books),
        )
    }

    /**
     * Resolves every DISTINCT raw genre string across [books] to its genre ids ONCE — running the
     * alias → normalize → auto-create cascade per distinct string (pre-creating new genres) in the
     * suspend prepare phase, before the batched write loop. The returned map keys each distinct string
     * by its normalized form (`raw.trim().lowercase()`) so the write loop can resolve a book's genre
     * ids from memory with the same de-dup key [resolveBookGenreIds] uses. Blank strings are skipped.
     *
     * This collapses the per-book genre transaction storm (a genred book was ~6 auto-committing
     * junction writes plus a suspend auto-create transaction) into one resolve per distinct string.
     */
    private suspend fun resolveScanGenres(books: Collection<AnalyzedBook>): Map<String, List<String>> {
        val distinctRaw =
            books
                .flatMap { it.genres }
                .filter { it.isNotBlank() }
                .associateBy { it.trim().lowercase() }
        return distinctRaw.mapValues { (_, raw) -> bookGenreWriter.resolveGenreIds(raw) }
    }

    /**
     * Resolves [analyzed]'s genre ids from the scan-wide pre-resolved [genres] map, de-duplicated and
     * order-preserving, using the same normalized key ([String.trim] + [String.lowercase]) the map was
     * built under in [resolveScanGenres]. Mirrors [BookGenreWriter.processGenreStrings]'s
     * case-insensitive de-dup so the batched junction write matches the per-book path exactly.
     */
    private fun resolveBookGenreIds(
        analyzed: AnalyzedBook,
        genres: Map<String, List<String>>,
    ): List<String> =
        analyzed.genres
            .filter { it.isNotBlank() }
            .distinctBy { it.trim().lowercase() }
            .flatMap { raw -> genres[raw.trim().lowercase()].orEmpty() }
            .distinct()

    /**
     * Batch-persists every changed book in [books] in chunked write transactions — the
     * performance-critical override of [BookIngestPort.resolveOrInsertAll].
     *
     * **Why this is fast.** A per-book [resolveOrInsert] loop costs, per book: two read transactions
     * (findByPath, sometimes findByInode), one write transaction for the aggregate, plus a separate
     * post-commit genre pass whose junction queries auto-commit individually (~6 commits for a genred
     * book). This collapses all of that:
     *
     *  1. **PREPARE (suspend, no write transaction).** Resolve everything that needs a suspend call
     *     ONCE: the contributor/series ids (already in [identityMaps]); the genre ids (already in
     *     [identityMaps.genres], pre-created up front); bulk book existence (one `IN (…)` read for
     *     paths, one for inodes — replacing the per-book findByPath/findByInode); the existing
     *     aggregates for the idempotency + cover-sticky checks (one batched read); and the cover-file
     *     stores (off-transaction I/O). Each book becomes a [PreparedBook] carrying its payload,
     *     genre ids, isNew flag, stored cover, and an idempotency-skip flag.
     *  2. **WRITE (chunked synchronous transactions).** Process the prepared books in chunks of
     *     [PERSIST_CHUNK_SIZE]; each chunk is ONE [suspendTransaction] whose synchronous body loops the
     *     books, each in its own nested `transactionWithResult` SAVEPOINT (per-book error containment),
     *     calling [upsertInOpenTransaction]. The result is O(chunks) write transactions, not O(books).
     *
     * Invariants preserved exactly from the per-book path: idempotent-rescan skip (unchanged content +
     * cover → no revision bump, no emit), cover sticky-UPLOADED, system-collection membership on
     * genuine-insert only, [FirehoseSuppressed] suppression (read once, threaded into every write),
     * per-book containment (a savepoint rollback logs + counts the book, never aborts the chunk), and
     * OOM abort (a compromised heap stops the loop and propagates via [PersistAbortedByOom]).
     */
    override suspend fun resolveOrInsertAll(
        libraryId: LibraryId,
        folderId: FolderId,
        books: List<AnalyzedBook>,
        coversByBook: Map<String, PendingCover>,
        systemCollectionId: String?,
        identityMaps: ScanIdentityMaps,
        onProgress: suspend (processed: Int, failed: Int) -> Unit,
    ): PersistResult {
        val (prepared, prepareFailed) =
            prepareBooks(libraryId, folderId, books, coversByBook, systemCollectionId, identityMaps)

        // Suppression is read ONCE in the suspend context and threaded into every write — the
        // synchronous chunk body cannot read the coroutine context (see upsertInOpenTransaction).
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null

        var persisted = 0
        // Books rejected in the prepare phase (e.g. an absolute rootRelPath) are already counted failed.
        var failed = prepareFailed
        val resolvedIds = mutableSetOf<BookId>()

        for (chunk in prepared.chunked(PERSIST_CHUNK_SIZE)) {
            // OOM mid-chunk wraps the partial counts: prior chunks (persisted/failed/resolvedIds) plus
            // this chunk's already-committed successes and the books that failed up to the OOM point.
            val succeeded =
                writeChunk(chunk, suppressed) { chunkSucceeded, chunkFailed ->
                    PersistResult(
                        persisted = persisted + chunkSucceeded.size,
                        failed = failed + chunkFailed,
                        resolvedIds = (resolvedIds + chunkSucceeded.map { it.bookId }).toSet(),
                    )
                }
            persisted += succeeded.size
            failed += chunk.size - succeeded.size
            succeeded.forEach { resolvedIds += it.bookId }

            // Scan tags reconcile AFTER the chunk's book rows commit, as a suspend post-pass — exactly
            // the per-book path's unconditional post-success writeScanTags. Add-only, so each call opens
            // its own substrate transaction (the BookTagWriter contract); a book with no tags is a no-op.
            // Suppression is inherited from the caller's coroutine context, matching the per-book path.
            bookTagWriter?.let { writer ->
                for (book in succeeded) {
                    if (book.tags.isNotEmpty()) writer.writeScanTags(book.bookId, book.tags)
                }
            }

            // Scan revival cascade (A3): any book in this chunk that revived a tombstoned row must get
            // its cascade-tombstoned junctions (tags/moods/collection memberships) revived too, floored
            // at each book's own deleted_at. Grouped by floor so books removed together share one call.
            // Post-commit, exactly like the tag pass above and the per-book path's cascade.
            succeeded
                .filter { it.revivedFromDeletedAt != null }
                .groupBy({ it.revivedFromDeletedAt!! }, { it.bookId.value })
                .forEach { (floor, ids) -> reviveBookJunctions(ids, floor) }

            onProgress(persisted + failed, failed)
        }
        return PersistResult(persisted = persisted, failed = failed, resolvedIds = resolvedIds)
    }

    /**
     * Writes one chunk of [PreparedBook]s in ONE [suspendTransaction], each book in its own nested
     * [app.cash.sqldelight.TransactionWithReturn] savepoint for per-book error containment. Returns the
     * books that committed (so the caller can count them + run their post-commit tag pass).
     *
     * A [book.skip][PreparedBook.skip] book writes only its genre junctions (the idempotent-content
     * re-scan still reconciles genres without a revision bump — matchesStoredContent normalizes genres
     * away, so a genre-only change is otherwise invisible); a non-skip book runs the full
     * [upsertInOpenTransaction] aggregate write with its [PreparedBook.extras] (cover, system-collection
     * membership, genre ids) mirrored onto the transaction thread.
     *
     * Per-book containment: a thrown book rolls back its savepoint (its afterCommit hooks discarded) and
     * is logged + dropped from the result, never aborting the chunk. An [OutOfMemoryError] aborts the
     * batch via [PersistAbortedByOom], carrying the partial counts [oomPartial] computes from this
     * chunk's already-committed successes and the books that failed up to the OOM point.
     */
    private suspend fun writeChunk(
        chunk: List<PreparedBook>,
        suppressed: Boolean,
        oomPartial: (chunkSucceeded: List<PreparedBook>, chunkFailed: Int) -> PersistResult,
    ): List<PreparedBook> {
        val succeeded = mutableListOf<PreparedBook>()
        var failedInChunk = 0
        suspendTransaction<Unit>(db) {
            for (book in chunk) {
                try {
                    db.transactionWithResult {
                        if (book.skip) {
                            bookGenreWriter.writeJunctions(book.bookId.value, book.genreIds)
                        } else {
                            setTransactionLocal(book.extras)
                            try {
                                upsertInOpenTransaction(book.payload, suppressed)
                            } finally {
                                setTransactionLocal(null)
                            }
                        }
                    }
                    succeeded += book
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    failedInChunk++
                    throw PersistAbortedByOom(oomPartial(succeeded.toList(), failedInChunk), e)
                } catch (e: Throwable) {
                    // Per-book savepoint rollback: this book's nested transaction rolled back (its
                    // afterCommit hooks discarded), the rest of the chunk is unaffected.
                    failedInChunk++
                    log.warn(e) { "Book persist threw: ${book.payload.rootRelPath} — continuing" }
                }
            }
        }
        return succeeded
    }

    /**
     * The PREPARE half of [resolveOrInsertAll] (suspend, no write transaction): resolves identity,
     * builds the payload, resolves genre ids, stores the cover file, and computes the idempotency-skip
     * + system-membership-on-insert flags for every book in [books] — everything that needs a suspend
     * call, done up front so the chunked write loop is purely synchronous. A book rejected in this
     * phase (an absolute rootRelPath — an upstream scanner bug) is dropped and counted in
     * [PreparedBatch.prepareFailed], never aborting the batch.
     */
    private suspend fun prepareBooks(
        libraryId: LibraryId,
        folderId: FolderId,
        books: List<AnalyzedBook>,
        coversByBook: Map<String, PendingCover>,
        systemCollectionId: String?,
        identityMaps: ScanIdentityMaps,
    ): PreparedBatch {
        // Reject absolute paths up front, contained per book: an absolute rootRelPath is an upstream
        // scanner bug (the natural key is library-relative), so the book is counted as failed and
        // dropped — never aborting the batch — matching the per-book require() + persistOne containment.
        val (valid, invalid) = books.partition { !it.candidate.rootRelPath.startsWith("/") }
        for (book in invalid) {
            log.warn { "rootRelPath must be library-relative, got absolute: ${book.candidate.rootRelPath} — skipping" }
        }

        // Bulk existence: one IN-read for natural keys, one for inodes — replacing the per-book
        // findByPath/findByInode read transactions with two reads for the whole scan.
        val byPath = bookFinder.findExistingByPaths(folderId, valid.map { it.candidate.rootRelPath })
        val inodes = valid.mapNotNull { it.candidate.identityInode() }
        val byInode = bookFinder.findExistingByInodes(folderId, inodes)

        // Resolve each book's stable BookId in memory from the bulk maps (natural key, then inode,
        // then a fresh UUID) — the same three-key model resolveOrInsert applies per book.
        data class Resolved(
            val analyzed: AnalyzedBook,
            val bookId: BookId,
            val isNew: Boolean,
        )

        val resolved =
            valid.map { analyzed ->
                val rootRelPath = analyzed.candidate.rootRelPath
                val inode = analyzed.candidate.identityInode()
                val existing = byPath[rootRelPath] ?: inode?.let { byInode[it] }
                Resolved(analyzed, existing ?: BookId(Uuid.random().toString()), isNew = existing == null)
            }

        // One batched read of the existing aggregates — drives the idempotency + cover-sticky checks
        // (replacing the per-book findById in upsertFromAnalyzed).
        val existingById =
            findAllByIds(resolved.filterNot { it.isNew }.map { it.bookId.value })
                .associateBy { it.id }

        val prepared =
            resolved.map { (analyzed, bookId, isNew) ->
                val pendingCover = coversByBook[analyzed.candidate.rootRelPath]
                val genreIds = resolveBookGenreIds(analyzed, identityMaps.genres)
                val payload =
                    buildPayloadFromAnalyzed(
                        bookId,
                        libraryId,
                        folderId,
                        analyzed,
                        identityMaps.contributors,
                        identityMaps.series,
                    )

                val existing = existingById[bookId.value]
                // Merge per-field user-edit provenance BEFORE the skip check (see [mergeUserEdits]):
                // the effective payload carries the restored protected scalars + the unioned set, and
                // the preserve flags ride into writePayload via the extras.
                val merge = existing?.let { mergeUserEdits(incoming = payload, existing = it) }
                val effectivePayload = (merge?.payload ?: payload).withSidecarCuration(analyzed.sidecarCuration)
                val pendingCoverHash = pendingCover?.bytes?.sha256Hex()
                val coverUnchanged =
                    existing?.cover?.source == CoverSource.UPLOADED ||
                        pendingCoverHash == existing?.cover?.hash
                // Revival guardrail: a byte-identical re-add over a TOMBSTONED book must never skip —
                // matchesStoredContent normalizes deletedAt away, so an idempotent-content match would
                // otherwise leave deleted_at set and the book dead. Forcing a write revives it
                // (updateContent sets deleted_at = NULL + bumps revision).
                val skip =
                    existing != null && existing.deletedAt == null &&
                        coverUnchanged && effectivePayload.matchesStoredContent(existing)

                val storedCover =
                    when {
                        skip -> null

                        // idempotent skip writes nothing
                        existing?.cover?.source == CoverSource.UPLOADED -> null

                        // sticky: preserve uploaded
                        else -> managedCoverFiles.storeCoverIfPresent(bookId, pendingCover)
                    }

                PreparedBook(
                    bookId = bookId,
                    payload = effectivePayload,
                    skip = skip,
                    genreIds = genreIds,
                    tags = analyzed.tags,
                    extras =
                        BookWriteExtras(
                            managedCover = storedCover,
                            systemCollectionId = if (isNew) systemCollectionId else null,
                            genreIds = genreIds,
                            preserveContributors = merge?.preserveContributors == true,
                            preserveSeries = merge?.preserveSeries == true,
                        ),
                    // Non-null only when reviving a tombstoned row (skip is always false in that case,
                    // so the write runs and clears deleted_at) — drives the A3 junction-revival cascade.
                    revivedFromDeletedAt = existing?.deletedAt,
                )
            }
        return PreparedBatch(prepared = prepared, prepareFailed = invalid.size)
    }

    /**
     * Builds a [BookSyncPayload] from [analyzed] under [bookId], resolving contributors/series from the
     * scan-wide pre-resolved [contributorIds]/[seriesIds] maps (falling back to a per-call resolve for
     * a name the bulk pass somehow missed, or for a single-book caller that passes null). Shared by
     * [resolveOrInsertAll]'s prepare phase and [upsertFromAnalyzed] so the payload-build shape lives in
     * one place.
     */
    private suspend fun buildPayloadFromAnalyzed(
        bookId: BookId,
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        contributorIds: Map<String, ContributorId>?,
        seriesIds: Map<String, SeriesId>?,
    ): BookSyncPayload {
        val resolvedContributors =
            analyzedBookMapper.buildContributors(analyzed).map { c ->
                // Prefer the scan-wide pre-resolved map (one bulk lookup before the persist loop);
                // fall back to a per-call resolveOrCreate for single-book callers (metadata apply).
                // The map key MUST match ContributorRepository's dedup key exactly.
                val id =
                    contributorIds?.get(contributorDedupKey(c.name, c.sortName))?.value
                        ?: contributorRepository.resolveOrCreate(c.name, c.sortName).value
                c.copy(id = id)
            }
        val resolvedSeries =
            analyzedBookMapper.buildSeries(analyzed).map { s ->
                val id =
                    seriesIds?.get(normalizeForDedup(s.name))?.value
                        ?: seriesRepository.resolveOrCreate(s.name).value
                s.copy(id = id)
            }
        return analyzedBookMapper.toBookSyncPayload(
            bookId = bookId,
            libraryId = libraryId,
            folderId = folderId,
            analyzed = analyzed,
            resolvedContributors = resolvedContributors,
            resolvedSeries = resolvedSeries,
        )
    }

    /**
     * Tombstones this book and cascade-soft-deletes all of its junction rows — `book_tags`,
     * `book_moods`, and `collection_books` — so clients receive per-row tombstones for the orphaned
     * junctions. Tombstoning the `collection_books` rows is what makes a removed book leave every
     * collection it was in (Continue-Listening/search/collection-visibility all key off live
     * memberships): a dead book no longer surfaces via any collection's list, count, or the
     * `accessibleBookIdsSql` grant branch. Each cascade opens its own transaction (matching the
     * per-row substrate contract), run after the book's own tombstone commits.
     *
     * Finally, the orphan-purge cascade ([orphanParentPurger]) tombstones any contributor / series /
     * genre / tag / mood the removal left with zero live book children, so an orphaned parent stops
     * appearing. The linked parents are captured BEFORE the removal (tombstone-inclusively, so the
     * capture survives a resume run), then re-evaluated after it.
     *
     * **Crash-resume.** Each step above commits in its own transaction, so a crash mid-cascade leaves a
     * half-cascaded book (tombstoned book, some live junctions, unpurged parents). Re-invoking
     * `softDelete` on an already-tombstoned book is safe and completes any unfinished cascade: the base
     * `softDelete` has no already-deleted early return, so it re-stamps the tombstone (bumping the
     * revision and re-emitting a convergent [SyncEvent]); the junction cascades are live-select
     * idempotent (already-tombstoned rows are skipped); and [OrphanParentPurger.captureParents] is
     * tombstone-inclusive, so the orphan purge still fires even when the junctions are already dead.
     * Caveat: nothing re-invokes this automatically today — the scan sweeps ([softDeleteAbsent] /
     * [softDeleteAbsentByPaths]) select LIVE books only — so an interrupted cascade heals only on an
     * explicit re-delete (a bulk folder-removal pass or a manual removal), never on its own.
     */
    override suspend fun softDelete(
        id: BookId,
        clientOpId: String?,
        userId: String?,
    ): AppResult<Unit> {
        val linkedParents = orphanParentPurger?.captureParents(id.value)
        val result = super.softDelete(id, clientOpId, userId)
        if (result is AppResult.Success) {
            bookTagRepository?.softDeleteAllForBook(id.value)
            bookMoodRepository?.softDeleteAllForBook(id.value)
            collectionBookRepository?.softDeleteAllForBook(id.value)
            if (linkedParents != null) orphanParentPurger.purgeOrphaned(linkedParents)
        }
        return result
    }

    /**
     * Revives the junction rows (`book_tags` / `book_moods` / `collection_books`) for [bookIds] that
     * were tombstoned at or after [cascadeFloor] — the same cascade [reviveByIds] runs for a folder
     * re-add, reused by the scan revival paths.
     *
     * A scan re-ingest of a removed book revives the book ROW ([updateContent] clears `deleted_at`) but
     * would otherwise leave its cascade-tombstoned junctions dead — so the book returned uncollected
     * (invisible under the pure-union rule) with the user's tags/moods silently gone. [cascadeFloor] is
     * the book's own `deleted_at`, so only junctions tombstoned BY that removal return; a membership the
     * user removed manually earlier (an older tombstone) stays dead. A no-op when [bookIds] is empty;
     * each repo opens its own transaction (the per-row substrate contract), run after the reviving book
     * write commits.
     */
    private suspend fun reviveBookJunctions(
        bookIds: List<String>,
        cascadeFloor: Long,
    ) {
        if (bookIds.isEmpty()) return
        bookTagRepository?.reviveAllForBooks(bookIds, cascadeFloor)
        bookMoodRepository?.reviveAllForBooks(bookIds, cascadeFloor)
        collectionBookRepository?.reviveAllForBooks(bookIds, cascadeFloor)
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
     * Tombstones every non-deleted book in [libraryId] whose `(folderId, rootRelPath)` locator is
     * not in [seen] — the folder-qualified, path-keyed counterpart to [softDeleteAbsent].
     *
     * Comparing folder-qualified pairs (not bare paths) closes a latent cross-folder aliasing bug:
     * two folders holding a book at the same relative path no longer mask each other in the sweep.
     *
     * **Full-scan only.** Same authoritativity contract as [softDeleteAbsent].
     */
    override suspend fun softDeleteAbsentByPaths(
        libraryId: LibraryId,
        seen: Set<FolderScopedPath>,
    ): Int {
        val toDelete =
            suspendTransaction(db) {
                db.booksQueries
                    .selectLiveIdsAndPathsForLibrary(libraryId.value)
                    .executeAsList()
                    .filterNot { FolderScopedPath(FolderId(it.folder_id), it.root_rel_path) in seen }
                    .map { it.id }
            }
        for (id in toDelete) {
            softDelete(BookId(id), clientOpId = null)
        }
        return toDelete.size
    }

    /**
     * Soft-deletes the live book at `(folderId, rootRelPath)`, if one exists.
     *
     * Idempotent: a no-op when no live (non-deleted) book exists at that folder-scoped path.
     */
    override suspend fun softDeleteByPath(
        folderId: FolderId,
        rootRelPath: String,
    ): Int {
        val id =
            suspendTransaction<BookId?>(db) {
                db.booksQueries
                    .selectLiveIdByPath(folderId.value, rootRelPath)
                    .executeAsOneOrNull()
                    ?.let { BookId(it) }
            } ?: return 0 // already gone — idempotent no-op
        softDelete(id, clientOpId = null)
        return 1
    }

    /**
     * All book ids under [folderId] (tombstone-inclusive) — drives folder-id-reuse revival when a
     * soft-deleted folder is re-added at the same path.
     */
    suspend fun idsByFolder(folderId: FolderId): List<BookId> =
        suspendTransaction(db) {
            db.booksQueries
                .selectIdsByFolder(folderId.value)
                .executeAsList()
                .map { BookId(it) }
        }

    /**
     * Ids of books under [folderId] tombstoned at or after [deletedAtFloor] — the folder-remove
     * cascade floor (the folder's own `deleted_at`). Drives folder-id-reuse revival scoped to the
     * removal: only books tombstoned BY the folder removal are returned, never zombies tombstoned by
     * an earlier scan (their files long gone), which must stay dead on re-add.
     */
    suspend fun idsByFolderDeletedSince(
        folderId: FolderId,
        deletedAtFloor: Long,
    ): List<BookId> =
        suspendTransaction(db) {
            db.booksQueries
                .selectIdsByFolderDeletedSince(folder_id = folderId.value, deleted_at = deletedAtFloor)
                .executeAsList()
                .map { BookId(it) }
        }

    /**
     * Revives a tombstoned book: clears `deleted_at` and bumps the revision so clients reflow it
     * as live, emitting a [SyncEvent.Updated] after commit. Used when a removed folder is re-added
     * under its reused id so the folder's books return under their original UUIDs, keeping every
     * client's saved references (playback position, shelves, collections) valid. Opens its own
     * transaction. A no-op-on-a-missing-row returns [SyncError.NotFound].
     */
    suspend fun reviveById(id: BookId): AppResult<Unit> {
        val idStr = idAsString(id)
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.reviveById(revision = rev, updated_at = now, id = idStr)
            if (db.booksQueries.changes().executeAsOne() == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = idStr))
            } else {
                publishUpdatedAfterCommit(idStr, rev, now)
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Batch-revives every book in [ids] (clears `deleted_at`, bumps a per-book revision, emits a
     * per-book [SyncEvent.Updated]) in ONE transaction — the batched counterpart to [reviveById] for
     * folder-id-reuse revival, where a re-added folder can carry thousands of books. Cascades to the
     * books' user tags via [bookTagRepository] (a second transaction), symmetric with [softDelete]'s
     * tag tombstone cascade, so a remove+re-add never loses a book's tags. Missing ids are skipped.
     *
     * [cascadeFloor] is the removed folder's own `deleted_at`, threaded down to the tag cascade so it
     * floors the tag revival exactly as it floors this book set (see [idsByFolderDeletedSince]): only
     * junctions tombstoned BY the folder removal return with the book, never a tag the user removed
     * manually before the folder was removed.
     */
    suspend fun reviveByIds(
        ids: List<BookId>,
        cascadeFloor: Long,
    ) {
        if (ids.isEmpty()) return
        suspendTransaction<Unit>(db) {
            for (id in ids) {
                val idStr = idAsString(id)
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                db.booksQueries.reviveById(revision = rev, updated_at = now, id = idStr)
                if (db.booksQueries.changes().executeAsOne() != 0L) {
                    publishUpdatedAfterCommit(idStr, rev, now)
                }
            }
        }
        // Symmetric with softDelete's junction tombstone cascade: restore each book's user tags,
        // moods, and collection memberships (each its own transaction, exactly as the tombstone
        // cascade is a separate call after the book write), floored on the folder-removal instant so
        // a remove-then-rescan keeps a book's memberships instead of losing them.
        val idValues = ids.map { it.value }
        bookTagRepository?.reviveAllForBooks(idValues, cascadeFloor)
        bookMoodRepository?.reviveAllForBooks(idValues, cascadeFloor)
        collectionBookRepository?.reviveAllForBooks(idValues, cascadeFloor)
    }

    /**
     * Builds a [BookSyncPayload] from [analyzed] under the supplied [bookId] and
     * writes the full aggregate through the substrate's `upsert`.
     *
     * Genres and scan tags are reconciled in **separate, sequential** transactions after
     * the book write commits (genres via the Exposed [bookGenreWriter], tags via
     * [bookTagWriter]).
     *
     * The book→system-collection membership for a genuinely-new book, by contrast, is written
     * **atomically** inside the book-insert transaction: when [systemCollectionId] is non-null and
     * this call INSERTs a new book, the id rides in via [BookWriteExtras] and `writePayload` lands
     * the `collection_books` row in the same SQLDelight transaction (see [writeSystemMembership]).
     * The id is stashed only when `existing == null`, so the UPDATE path of a rescan can never
     * re-collect a book into a system collection. Atomicity closes the REST-catch-up window a
     * post-commit membership write would otherwise open — a held book is never momentarily
     * pullable by a member.
     */
    suspend fun upsertFromAnalyzed(
        bookId: BookId,
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover? = null,
        systemCollectionId: String? = null,
        contributorIds: Map<String, ContributorId>? = null,
        seriesIds: Map<String, SeriesId>? = null,
    ): AppResult<BookSyncPayload> {
        val payload =
            buildPayloadFromAnalyzed(bookId, libraryId, folderId, analyzed, contributorIds, seriesIds)
        // Read the existing aggregate ONCE — drives the idempotency check, the cover-source
        // sticky-UPLOADED skip, and the only-on-create system-collection membership gate.
        val existing = findById(bookId)
        val isNew = existing == null
        // Merge per-field user-edit provenance BEFORE the skip check: protected scalars are restored
        // onto the effective payload (so a protected-only rescan matches stored and skips), and the
        // contributor/series preserve flags ride into writePayload via the extras.
        val merge = existing?.let { mergeUserEdits(incoming = payload, existing = it) }
        val effectivePayload = (merge?.payload ?: payload).withSidecarCuration(analyzed.sidecarCuration)
        val pendingCoverHash = pendingCover?.bytes?.sha256Hex()
        val coverUnchanged =
            existing?.cover?.source == CoverSource.UPLOADED ||
                pendingCoverHash == existing?.cover?.hash
        val result: AppResult<BookSyncPayload> =
            if (existing != null && existing.deletedAt == null &&
                coverUnchanged && effectivePayload.matchesStoredContent(existing)
            ) {
                // Idempotent re-scan: content identical to what's stored — skip the
                // revision-bumping upsert AND the cover file-write. A tombstoned existing row
                // (deletedAt != null) never skips: the write must revive it (deleted_at = NULL).
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
                // A genuinely-new book assigned to a system collection (ALL_BOOKS or INBOX) has its
                // `collection_books` membership written ATOMICALLY inside writePayload's INSERT branch
                // (see writeSystemMembership), so the book is collected the instant it exists. The
                // `book.Created` emit fires normally — the firehose's delivery-time access filter sees
                // the book already collected, so a held (INBOX) book is correctly excluded from members.
                // The id rides in via BookWriteExtras; it is stashed only for a genuinely-new book
                // (`isNew`), never on the UPDATE path, so a rescan can't re-collect a released book.
                withContext(
                    TransactionLocal(
                        BookWriteExtras(
                            managedCover = storedCover,
                            systemCollectionId = if (isNew) systemCollectionId else null,
                            preserveContributors = merge?.preserveContributors == true,
                            preserveSeries = merge?.preserveSeries == true,
                        ),
                    ),
                ) {
                    upsert(effectivePayload, clientOpId = null)
                }
            }
        if (result is AppResult.Success) {
            // Scan revival cascade (A3): when this write revived a tombstoned row (existing.deletedAt
            // was set, so the write cleared it via updateContent's deleted_at = NULL), restore the
            // book's cascade-tombstoned junctions too — floored at its own deleted_at — so a transient
            // remove→re-add never returns the book uncollected with its tags/moods lost.
            existing?.deletedAt?.let { floor -> reviveBookJunctions(listOf(bookId.value), floor) }
            val now = clock.now().toEpochMilliseconds()
            // Genres: a separate, sequential pass over SQLDelight (idempotent, no revision bump).
            // The writer's synchronous junction queries auto-commit and the auto-create upsert runs
            // its own transaction — sequential after the book write, so no SQLITE_BUSY contention.
            // Skip when GENRES is user-protected (hand-edited or applied via enrichment): a rescan's
            // file-derived genres must not silently revert the curated set (A7).
            if (merge?.preserveGenres != true) {
                bookGenreWriter.processGenreStrings(bookId, analyzed.genres, now)
            }
            bookTagWriter?.writeScanTags(bookId, analyzed.tags)
        }
        return result
    }

    /**
     * The outcome of merging an incoming write against the stored book's per-field user-edit
     * provenance: the [payload] to persist (protected scalar fields restored to their stored values,
     * [BookSyncPayload.userEditedFields] carrying the union) plus the contributor/series preserve
     * flags [writePayload] honours via the [BookWriteExtras].
     */
    private class UserEditMerge(
        val payload: BookSyncPayload,
        val preserveContributors: Boolean,
        val preserveSeries: Boolean,
        val preserveGenres: Boolean,
    )

    /**
     * Merges an [incoming] write against the [existing] stored aggregate so a user's hand-edits
     * survive a rescan (per-field user-edit provenance — the generalization of the sticky-chapters /
     * sticky-uploaded-cover guards to the five edit-protected metadata fields).
     *
     * For every field the user has edited but THIS write isn't itself re-editing —
     * `existing.userEditedFields − incoming.userEditedFields` — the stored value wins: scalar fields
     * (title/subtitle/description) are restored onto the returned [UserEditMerge.payload]; the
     * contributor/series collections are flagged so [writePayload] skips their replace. The persisted
     * [BookSyncPayload.userEditedFields] is the union, so protection is sticky — a scan (empty set)
     * never removes it, while a genuine user edit both writes the new value AND keeps the field
     * protected. Computing the merge BEFORE the [matchesStoredContent] skip check is load-bearing: a
     * rescan that only "disagrees" on a protected scalar then matches stored and naturally skips, with
     * no revision bump or firehose event — no separate provenance branch in [matchesStoredContent].
     */
    private fun mergeUserEdits(
        incoming: BookSyncPayload,
        existing: BookSyncPayload,
    ): UserEditMerge {
        val stillProtected = existing.userEditedFields - incoming.userEditedFields

        fun <T> kept(
            field: UserEditedField,
            stored: T,
            scanned: T,
        ): T = if (field in stillProtected) stored else scanned
        val merged =
            incoming.copy(
                title = kept(UserEditedField.TITLE, existing.title, incoming.title),
                subtitle = kept(UserEditedField.SUBTITLE, existing.subtitle, incoming.subtitle),
                description = kept(UserEditedField.DESCRIPTION, existing.description, incoming.description),
                publisher = kept(UserEditedField.PUBLISHER, existing.publisher, incoming.publisher),
                language = kept(UserEditedField.LANGUAGE, existing.language, incoming.language),
                publishYear = kept(UserEditedField.PUBLISH_YEAR, existing.publishYear, incoming.publishYear),
                userEditedFields = existing.userEditedFields + incoming.userEditedFields,
            )
        return UserEditMerge(
            payload = merged,
            preserveContributors = UserEditedField.CONTRIBUTORS in stillProtected,
            preserveSeries = UserEditedField.SERIES in stillProtected,
            preserveGenres = UserEditedField.GENRES in stillProtected,
        )
    }

    /**
     * Applies [curation] — re-ingested from an external `listenup.json` sidecar — onto a
     * scan payload AFTER [mergeUserEdits] ran. Strictly *additive*: the sidecar's
     * [SidecarCuration.userEditedFields] union into the payload's set (restoring
     * rescan-protection after a DB wipe) and its USER chapters, when present, replace the
     * scan-derived chapter set with `chapterSource = USER` (writePayload's sticky-chapters
     * guard admits USER-sourced incoming chapters). Running after the merge is load-bearing:
     * the sidecar's fields must never count as *incoming re-edits*, or a scan could clobber
     * a stored protected value with a scanner-derived one.
     */
    private fun BookSyncPayload.withSidecarCuration(curation: SidecarCuration?): BookSyncPayload {
        if (curation == null) return this
        val withProvenance = copy(userEditedFields = userEditedFields + curation.userEditedFields)
        val userChapters = curation.userChapters ?: return withProvenance
        return withProvenance.copy(
            chapters = userChapters.toChapterPayloads(totalDuration),
            chapterSource = ChapterSource.USER,
        )
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
     * Batch-reads the full book aggregates for [bookIds] in input order, skipping ids whose
     * root row is absent. One round-trip per child table per id-chunk (chunked at 900) — the
     * batched alternative to a per-id [findById] loop.
     *
     * Used by the cross-aggregate Contributor/Series merge/delete/unmerge service flows to
     * re-upsert every affected book after a junction relink without an N+1. Opens its own
     * SQLDelight transaction; when called from inside an already-open transaction (the service
     * flows do) it nests as a savepoint on the same connection.
     */
    suspend fun findAllByIds(bookIds: List<String>): List<BookSyncPayload> =
        suspendTransaction(db) { readPayloads(bookIds) }

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
     * Rewrites [id]'s `root_rel_path` alone — the organizer's DB-side move step (see
     * `com.calypsan.listenup.server.organize.MoveManifestExecutor`). Called only AFTER the
     * corresponding files have already landed at their new on-disk location via the
     * `LibraryWriteBroker` — this never touches the filesystem itself, and never touches any
     * content column, so a rescan's tombstone sweep and the book's identity are otherwise
     * untouched. Bumps revision and publishes [SyncEvent.Updated] like [touchRevision], since
     * `rootRelPath` is part of the syncable [BookSyncPayload].
     */
    suspend fun moveRootRelPath(
        id: BookId,
        newRootRelPath: String,
    ): AppResult<Unit> {
        val idStr = idAsString(id)
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.updateRootRelPath(
                root_rel_path = newRootRelPath,
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
     * Batched [touchRevision]: bumps every book in [ids] in ONE transaction, assigning each row its
     * own revision from the global counter — never one shared revision, because `pullSince` pages by
     * `revision > cursor`, so equal revisions straddling a page boundary would be skipped. Missing ids
     * are skipped (mirrors [reviveByIds]); an empty list is a no-op success. The bulk collection paths
     * call this instead of looping [touchRevision] to collapse N transactions into one.
     */
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
     * Writes the `(systemCollectionId, bookId)` membership into `collection_books` inside the
     * open SQLDelight book transaction, atomically with the book insert (pure-union model).
     *
     * [systemCollectionId] is exactly one of the library's two system collections, resolved upstream
     * by [com.calypsan.listenup.server.services.BookPersister.resolveSystemCollectionId]: ALL_BOOKS
     * when the inbox gate is off (the new book is immediately visible to every member via the default
     * ALL_BOOKS grant), or INBOX when the gate is on (the new book is quarantined, admin-only). The
     * two are mutually exclusive — a held book joins INBOX only, never ALL_BOOKS — so the quarantine
     * invariant (a member never observes a held book as accessible) holds by construction.
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
     * @throws IllegalArgumentException when a system-collection id was stashed but no
     *   [CollectionBookRepository] is wired — a misconfiguration that would silently drop the
     *   membership (re-opening the firehose leak for a held book, or leaving a non-held book
     *   uncollected and invisible to members), so it fails loudly.
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
        // Idempotency: never re-insert an existing pair (defensive on the new-book INSERT path).
        if (db.collectionBooksQueries.existsByCollectionAndBook(systemCollectionId, bookId).executeAsOne()) {
            return
        }
        val syntheticId = "$systemCollectionId:$bookId"
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
                        collectionId = systemCollectionId,
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

/**
 * Converts sidecar-curated chapters (title + startMs only) to persistable chapter rows:
 * each chapter's duration runs to the next chapter's start, the last to [totalDurationMs].
 */
private fun List<SidecarCurationChapter>.toChapterPayloads(totalDurationMs: Long): List<BookChapterPayload> {
    val sorted = sortedBy { it.startMs }
    return sorted.mapIndexed { index, chapter ->
        val end = sorted.getOrNull(index + 1)?.startMs ?: totalDurationMs
        BookChapterPayload(
            id = "",
            title = chapter.title,
            duration = (end - chapter.startMs).coerceAtLeast(0L),
            startTime = chapter.startMs,
        )
    }
}
