package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookGenrePayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.BookAudioFileTable
import com.calypsan.listenup.server.db.BookChapterTable
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.GenreAliasTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.PendingBookGenreTable
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverInfo
import com.calypsan.listenup.server.cover.ManagedCoverFiles
import com.calypsan.listenup.server.cover.StoredCoverInfo
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import com.calypsan.listenup.server.sync.nextRevision
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

private val log = KotlinLogging.logger {}

/**
 * Server-side repository for the books aggregate.
 *
 * Extends the [SyncableRepository] substrate, owning the multi-table read/write
 * for a book + its contributors + series + chapters + audio files. The substrate
 * orchestrates revision bumping and change-bus publication; this class
 * implements [readPayload] and [writePayload] to manage the aggregate shape.
 *
 * `idAsString(BookId) = id.value` is load-bearing — the substrate's default
 * `toString()` on a value class returns `"BookId(value=foo)"`, which would
 * corrupt every column the id is written to. The Konsist rule
 * `IdAsStringRequiredForValueClassIdsRule` enforces this override at build time.
 *
 * @param contributorRepository the syncable contributors catalogue;
 *   [upsertFromAnalyzed] resolves each author/narrator name through it to a
 *   stable [com.calypsan.listenup.core.ContributorId] before the aggregate write.
 * @param seriesRepository the syncable series catalogue;
 *   [upsertFromAnalyzed] resolves each series name through it before the
 *   aggregate write.
 */
class BookRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val analyzedBookMapper: AnalyzedBookMapper = AnalyzedBookMapper(),
    clock: Clock = Clock.System,
    private val collectionBookRepository: com.calypsan.listenup.server.sync.CollectionBookRepository? = null,
    private val bookTagRepository: com.calypsan.listenup.server.sync.BookTagRepository? = null,
    private val homeDir: Path? = null,
    private val coverImageStore: CoverImageStore? = null,
) : SyncableRepository<BookSyncPayload, BookId>(
        db = db,
        table = BookTable,
        bus = bus,
        registry = registry,
        domainName = "books",
        clock = clock,
    ),
    BookIngestPort {
    /** Cover file and path helpers — file I/O and path resolution outside the sync seam. */
    private val managedCoverFiles = ManagedCoverFiles(coverImageStore, homeDir, db)

    override val elementSerializer: KSerializer<BookSyncPayload> = BookSyncPayload.serializer()

    override fun idAsString(id: BookId): String = id.value

    override val BookSyncPayload.id: BookId
        get() = BookId(this.id)

    override fun BookSyncPayload.revisionOf(): Long = revision

    /**
     * Per-book managed covers pending write in [writePayload]. Keyed by book-id string.
     *
     * [upsertWithManagedCover] inserts an entry for the target book-id, calls [upsert]
     * (which calls [writePayload] synchronously inside its transaction), then removes the
     * entry in a `finally` block — so the map is always leak-free even on failure.
     *
     * Using a [java.util.concurrent.ConcurrentHashMap] keyed by book-id eliminates the
     * data-corruption race that the old `@Volatile` single-field approach had: two
     * concurrent `upsertFromAnalyzed` calls on DIFFERENT books now have distinct keys
     * and cannot clobber each other's pending cover.
     */
    private val pendingManagedCovers = java.util.concurrent.ConcurrentHashMap<String, StoredCoverInfo>()

    /**
     * Per-book inbox collection ids (book-id → inbox id) pending an atomic membership write in
     * [writePayload]'s INSERT branch — stashed by [upsertFromAnalyzed] only for a NEW book and
     * cleared in a `finally`, mirroring [pendingManagedCovers]. Same-transaction membership
     * closes the TOCTOU firehose leak (a member's delivery-time `canAccess` sees it quarantined).
     */
    private val pendingInboxIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Reads the book aggregate by id — joins child tables for contributors,
     * series, chapters, and audio files, and constructs the cover payload from
     * the root row's `coverSource` + `coverHash` columns. Returns null when the
     * book row is absent.
     *
     * Bound to the open Exposed transaction opened by the substrate's
     * `upsert` / `pullSince` / etc.; child queries iterate by `ordinal` so the
     * on-wire shape preserves the canonical order across upserts.
     */
    override suspend fun readPayload(idStr: String): BookSyncPayload? {
        val bookRow =
            BookTable
                .selectAll()
                .where { BookTable.id eq idStr }
                .firstOrNull() ?: return null

        val contributors =
            (BookContributorTable innerJoin ContributorTable)
                .selectAll()
                .where { BookContributorTable.bookId eq idStr }
                .orderBy(BookContributorTable.ordinal)
                .map { row ->
                    BookContributorPayload(
                        id = row[ContributorTable.id],
                        name = row[ContributorTable.name],
                        sortName = row[ContributorTable.sortName],
                        role = row[BookContributorTable.role],
                        creditedAs = row[BookContributorTable.creditedAs],
                    )
                }

        val series =
            (BookSeriesMembershipTable innerJoin BookSeriesTable)
                .selectAll()
                .where { BookSeriesMembershipTable.bookId eq idStr }
                .orderBy(BookSeriesMembershipTable.ordinal)
                .map { row ->
                    BookSeriesPayload(
                        id = row[BookSeriesTable.id],
                        name = row[BookSeriesTable.name],
                        sequence = row[BookSeriesMembershipTable.sequence],
                    )
                }

        val chapters =
            BookChapterTable
                .selectAll()
                .where { BookChapterTable.bookId eq idStr }
                .orderBy(BookChapterTable.ordinal)
                .map { row ->
                    BookChapterPayload(
                        id = row[BookChapterTable.id],
                        title = row[BookChapterTable.title],
                        duration = row[BookChapterTable.duration],
                        startTime = row[BookChapterTable.startTime],
                    )
                }

        val genres =
            (BookGenreTable innerJoin GenreTable)
                .selectAll()
                .where { (BookGenreTable.bookId eq idStr) and GenreTable.deletedAt.isNull() }
                .orderBy(GenreTable.path)
                .map { row ->
                    BookGenrePayload(
                        id = row[GenreTable.id],
                        name = row[GenreTable.name],
                        slug = row[GenreTable.slug],
                        path = row[GenreTable.path],
                    )
                }

        val audioFiles =
            BookAudioFileTable
                .selectAll()
                .where { BookAudioFileTable.bookId eq idStr }
                .orderBy(BookAudioFileTable.ordinal)
                .map { row ->
                    BookAudioFilePayload(
                        id = row[BookAudioFileTable.id],
                        index = row[BookAudioFileTable.ordinal],
                        filename = row[BookAudioFileTable.filename],
                        format = row[BookAudioFileTable.format],
                        codec = row[BookAudioFileTable.codec],
                        duration = row[BookAudioFileTable.duration],
                        size = row[BookAudioFileTable.size],
                    )
                }

        val cover =
            bookRow[BookTable.coverHash]?.let { hash ->
                val coverSrc =
                    bookRow[BookTable.coverSource]?.let { raw ->
                        CoverSource.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    }
                coverSrc?.let { CoverPayload(source = it, hash = hash) }
            }

        return BookSyncPayload(
            id = bookRow[BookTable.id],
            libraryId = LibraryId(bookRow[BookTable.libraryId]),
            folderId = FolderId(bookRow[BookTable.folderId]),
            title = bookRow[BookTable.title],
            sortTitle = bookRow[BookTable.sortTitle],
            subtitle = bookRow[BookTable.subtitle],
            description = bookRow[BookTable.description],
            publishYear = bookRow[BookTable.publishYear],
            publisher = bookRow[BookTable.publisher],
            language = bookRow[BookTable.language],
            isbn = bookRow[BookTable.isbn],
            asin = bookRow[BookTable.asin],
            abridged = bookRow[BookTable.abridged],
            explicit = bookRow[BookTable.explicit],
            hasScanWarning = bookRow[BookTable.hasScanWarning],
            totalDuration = bookRow[BookTable.totalDuration],
            cover = cover,
            rootRelPath = bookRow[BookTable.rootRelPath],
            inode = bookRow[BookTable.inode],
            scannedAt = bookRow[BookTable.scannedAt],
            contributors = contributors,
            series = series,
            genres = genres,
            audioFiles = audioFiles,
            chapters = chapters,
            revision = bookRow[BookTable.revision],
            updatedAt = bookRow[BookTable.updatedAt],
            createdAt = bookRow[BookTable.createdAt],
            deletedAt = bookRow[BookTable.deletedAt],
        )
    }

    /**
     * Writes the full book aggregate inside the substrate's open transaction.
     *
     * **Atomicity is the contract.** All five surfaces — the root row, the four
     * child tables (contributors, series, chapters, audio files), and the FTS
     * index (`book_search` + `book_search_map`) — land together or not at all.
     * The substrate has already opened the transaction and resolved [existed];
     * this method issues DSL writes that bind to that transaction.
     *
     * Child rows are replaced wholesale (delete-then-insert) on every upsert.
     * The on-wire shape carries the canonical order; preserving it on disk via
     * `ordinal` is cheaper than computing a structural diff to skip touched
     * rows that didn't change.
     *
     * Contributor and series junction rows reference the payload's ids directly.
     * Those ids MUST already exist in [ContributorTable] / [BookSeriesTable] —
     * see [replaceContributors] / [replaceSeries]. The only path that builds a
     * [BookSyncPayload] (`upsertFromAnalyzed`) satisfies this by resolving every
     * contributor/series through the syncable catalogues before calling `upsert`.
     */
    override suspend fun writePayload(
        value: BookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        // Read the managed cover registered by upsertWithManagedCover (null for all other callers).
        // Do NOT remove here — the finally block in upsertWithManagedCover owns the cleanup.
        val managedCover = pendingManagedCovers[value.id]

        if (existed) {
            // Sticky-upload merge: if the existing row carries a user-uploaded cover
            // (cover_source = 'uploaded'), preserve the cover columns so a re-scan
            // does not clobber an intentional user choice. Any other existing source
            // (filesystem, embedded, enriched) gets overwritten by the new scan data.
            val existingCoverSource =
                BookTable
                    .selectAll()
                    .where { BookTable.id eq value.id }
                    .firstOrNull()
                    ?.get(BookTable.coverSource)
            val isUploadedLocked = existingCoverSource == CoverSource.UPLOADED.name.lowercase()

            BookTable.update({ BookTable.id eq value.id }) { stmt ->
                applyBookFields(stmt, value, preserveCoverColumns = isUploadedLocked, managedCover = managedCover)
                stmt[BookTable.revision] = rev
                stmt[BookTable.updatedAt] = now
                stmt[BookTable.deletedAt] = null
                stmt[BookTable.clientOpId] = clientOpId
            }
        } else {
            BookTable.insert { stmt ->
                stmt[BookTable.id] = value.id
                // libraryId + folderId come from the payload; the legacy registry
                // was the Books-A single-library resolver and is no longer the
                // source of truth here.
                stmt[BookTable.libraryId] = value.libraryId.value
                stmt[BookTable.folderId] = value.folderId.value
                applyBookFields(stmt, value, preserveCoverColumns = false, managedCover = managedCover)
                stmt[BookTable.revision] = rev
                stmt[BookTable.createdAt] = now
                stmt[BookTable.updatedAt] = now
                stmt[BookTable.deletedAt] = null
                stmt[BookTable.clientOpId] = clientOpId
            }
            // Atomic inbox quarantine: insertInboxMembership's upsert joins THIS transaction.
            // A stashed inbox id without a wired repo is a misconfiguration — fail loudly rather
            // than silently drop the quarantine (which would re-open the firehose leak).
            pendingInboxIds[value.id]?.let { inboxId ->
                val repo =
                    requireNotNull(collectionBookRepository) {
                        "inbox quarantine requested but CollectionBookRepository is not wired"
                    }
                insertInboxMembership(repo, inboxId, value.id, now)
            }
        }

        replaceContributors(value.id, value.contributors)
        replaceSeries(value.id, value.series)
        replaceChapters(value.id, value.chapters)
        replaceAudioFiles(value.id, value.audioFiles)
        upsertFtsRow(value)
    }

    // --- Identity resolution -------------------------------------------------

    /**
     * Resolves an [AnalyzedBook] from the scanner to a stable [BookId] and writes
     * its aggregate, using the three-key identity model (spec §5.1):
     *
     *  1. **Natural key** `(library_id, root_rel_path)` — the path the scanner
     *     produces. A hit means a plain rescan; the existing UUID is reused and
     *     the aggregate is refreshed in place.
     *  2. **Move-detection hint** `(library_id, inode)` — checked only when the
     *     natural-key lookup misses. A hit means the book's directory was
     *     renamed/moved; the existing UUID is preserved, `root_rel_path` is
     *     updated to the new value, and the move is logged at INFO so operators
     *     can audit it. The book-level inode is the first audio file's inode
     *     ([CandidateBook.files]`.first().inode`); a null inode skips this
     *     branch entirely (filesystems without stable file keys).
     *  3. **No match** — a genuinely new book; a fresh UUID is allocated.
     *
     * In every branch the write goes through [upsertFromAnalyzed], which builds
     * a [BookSyncPayload] and hands it to the substrate's `upsert` — so revision
     * bumping and `SyncEvent` publication happen uniformly. Each lookup opens
     * its own short read transaction; the subsequent write opens its own. SQLite
     * is single-writer, so the consecutive transactions serialize cleanly with
     * no risk of a lost-update race within a single scan pass.
     *
     * @return [AppResult.Success] carrying an [IngestOutcome] — the stable
     *   [BookId] (newly minted or pre-existing) plus a `wasNew` flag the scan
     *   coordinator uses to gate inbox auto-add — only when the aggregate write
     *   landed. An [AppResult.Failure] means [upsertFromAnalyzed] did not persist
     *   the book; callers must not treat the failure as a persisted aggregate,
     *   and for a new book the minted UUID points at nothing.
     */
    override suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
        inboxCollectionId: String?,
    ): AppResult<IngestOutcome> {
        val rootRelPath = analyzed.candidate.rootRelPath

        findByPath(libraryId, rootRelPath)?.let { existing ->
            return upsertFromAnalyzed(existing, libraryId, folderId, analyzed, pendingCover, inboxCollectionId)
                .map { IngestOutcome(existing, wasNew = false) }
        }

        analyzed.candidate.files
            .firstOrNull()
            ?.inode
            ?.let { inode ->
                findByInode(libraryId, inode)?.let { existing ->
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
     *
     * The cascade runs after the book row is tombstoned — the `book_tags` soft-deletes
     * each bump their own revision and publish [com.calypsan.listenup.api.sync.SyncEvent.Deleted]
     * to the change bus, matching the behaviour of an explicit tag removal.
     *
     * The book's FTS row is excluded by the existing `books_ad` trigger; no explicit
     * `book_search` reindex is needed here.
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
     * Tombstones every non-deleted book in [libraryId] that a completed FULL
     * scan did not see — the book's directory was deleted or moved out of the
     * library tree (spec §5.4).
     *
     * **Full-scan only.** A book's absence from [seenIds] is meaningful only
     * when the scanner walked the entire library: an incremental scan visits a
     * subtree, so books outside that subtree are absent for a benign reason and
     * must not be swept. Incremental scans must never call this method.
     *
     * Each swept book is removed through the substrate's [softDelete], so every
     * sweep emits exactly one [com.calypsan.listenup.api.sync.SyncEvent.Deleted]
     * per book on the change bus, with a bumped revision — clients apply the
     * tombstone like any other delete. Books already carrying a `deletedAt`
     * tombstone are excluded by the query, so a re-sweep neither re-bumps a
     * revision nor re-emits a Deleted event.
     *
     * The to-delete ids are read in one short transaction; each [softDelete]
     * then opens its own transaction. `softDelete` is not wrapped in an outer
     * transaction here — doing so would nest transactions needlessly.
     */
    override suspend fun softDeleteAbsent(
        libraryId: LibraryId,
        seenIds: Set<BookId>,
    ) {
        val seenSet = seenIds.mapTo(mutableSetOf()) { it.value }
        val toDelete =
            suspendTransaction(db) {
                BookTable
                    .selectAll()
                    .where {
                        (BookTable.libraryId eq libraryId.value) and BookTable.deletedAt.isNull()
                    }.map { it[BookTable.id] }
                    .filterNot { it in seenSet }
            }
        for (id in toDelete) {
            softDelete(BookId(id), clientOpId = null)
        }
    }

    /**
     * Builds a [BookSyncPayload] from [analyzed] under the supplied [bookId] and
     * writes the full aggregate through the substrate's `upsert`.
     *
     * The mapping flattens the scanner's resolved view onto the wire shape:
     *  - `authors` + `narrators` become contributor rows (`role = "author"` /
     *    `"narrator"`); each is resolved through [ContributorRepository.resolveOrCreate]
     *    by normalized name, so the junction rows reference an existing catalogue id.
     *  - `series` entries map one-to-one to series memberships, each resolved
     *    through [SeriesRepository.resolveOrCreate] the same way.
     *  - `tracks` map to audio files; `filename`/`format`/`size` come from the
     *    track's [com.calypsan.listenup.api.dto.scanner.FileEntry].
     *  - `chapters` map to chapter rows (`duration = endMs - startMs`).
     *
     * **Duration caveat.** The Scanner's `AnalyzedBook` carries no per-track
     * duration — `TrackEntry` wraps only a `FileEntry` (path/size/inode), and
     * `codec` is not surfaced anywhere. The single authoritative duration is
     * `embedded.durationMs`, produced by the parser for the *primary* audio
     * file only (spec §3 non-goal: multi-file books parse the first file).
     * So `totalDuration` and the first audio file's `duration` carry that
     * value; non-primary files get `0L`; `codec` is left blank. Phase 2's
     * per-file duration backfill (or Books-B) is the natural home for richer
     * audio-file metadata — flagged for the Task 11/15 implementers.
     *
     * `pendingCover` is the raw cover bytes from the scanner. When non-null and the existing
     * row does not carry an UPLOADED cover, the bytes are stored to the managed cover store
     * (file I/O outside the DB transaction) and the resulting [StoredCoverInfo] is written
     * atomically with the rest of the book row — one write, one revision bump, one [SyncEvent].
     * When the existing row carries an UPLOADED cover, [pendingCover] is discarded so no orphan
     * file is written and the user's uploaded cover is preserved.
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
        // Read the existing aggregate ONCE — derives the existing cover source (sticky-UPLOADED
        // skip) and isNew (the only-on-create gate for atomic inbox quarantine).
        val existing = findById(bookId)
        val existingCoverSource = existing?.cover?.source
        val isNew = existing == null
        // File I/O must stay OUTSIDE the DB transaction — store the cover first, then upsert.
        val storedCover =
            if (existingCoverSource == CoverSource.UPLOADED) {
                null // sticky: skip file write + preserve the uploaded cover in writePayload
            } else {
                managedCoverFiles.storeCoverIfPresent(bookId, pendingCover)
            }
        // Stash the inbox id ONLY for a new book; writePayload commits the membership atomically.
        if (isNew && inboxCollectionId != null) pendingInboxIds[bookId.value] = inboxCollectionId
        val result =
            try {
                upsertWithManagedCover(payload, managedCover = storedCover)
            } finally {
                pendingInboxIds.remove(bookId.value)
            }
        if (result is AppResult.Success) {
            val now = clock.now().toEpochMilliseconds()
            suspendTransaction(db) { processGenreStrings(bookId, analyzed.genres, now) }
        }
        return result
    }

    /**
     * Resolves the scanner's raw genre strings for [bookId] through a 3-step
     * cascade, in precedence order:
     *  1. **Curator alias** — a [GenreAliasTable] row maps the raw string to a
     *     genre id (→ `book_genres`). Custom mappings always win.
     *  2. **Built-in normalization** — [GenreNormalizer] turns the raw string
     *     into canonical slug(s), each resolved against the live taxonomy via
     *     [GenreTable.findBySlug] (→ `book_genres`).
     *  3. **Unresolved** — nothing matched, so the string queues for curator
     *     mapping in [PendingBookGenreTable].
     *
     * Idempotent on rescan — wipes the prior `book_genres` and
     * `pending_book_genres` rows for the book before re-writing, so a book
     * whose `metadata.json` lost a string no longer appears as its source.
     *
     * Inputs are case-insensitive-deduped (`.lowercase().trim()`) so scanning
     * `["Fantasy", "fantasy", "FANTASY"]` yields a single junction row even
     * before the alias's `COLLATE NOCASE` lookup runs. Blank strings are
     * skipped.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    private fun processGenreStrings(
        bookId: BookId,
        rawStrings: List<String>,
        now: Long,
    ) {
        val bookIdStr = bookId.value
        BookGenreTable.deleteAllForBook(bookIdStr)
        PendingBookGenreTable.replacePendingForBook(bookIdStr, emptyList(), now)

        for (raw in rawStrings.distinctBy { it.trim().lowercase() }) {
            if (raw.isBlank()) continue
            resolveGenreString(bookIdStr, raw, now)
        }
    }

    /**
     * Reads the full book aggregate for [id], or null when absent. Opens its own
     * read transaction — usable outside the substrate's `upsert`/`pullSince`
     * orchestration (the scanner reads a book's current `rootRelPath` before
     * logging a move; tests assert post-write state).
     */
    suspend fun findById(id: BookId): BookSyncPayload? = suspendTransaction(db) { readPayload(id.value) }

    /**
     * Calls [upsert] with [managedCover] threaded through [writePayload] via [pendingManagedCovers].
     *
     * Inserts the cover into [pendingManagedCovers] under the payload's book-id key, then calls
     * [upsert] (which calls [writePayload] synchronously within its transaction). The `finally`
     * block removes the entry unconditionally — guaranteeing no map-entry leak even when [upsert]
     * throws or is cancelled.
     *
     * Using the book-id as key makes concurrent scans of DIFFERENT books safe: each book's cover
     * lives under its own key and cannot overwrite another book's entry.
     */
    private suspend fun upsertWithManagedCover(
        payload: BookSyncPayload,
        managedCover: StoredCoverInfo?,
    ): AppResult<BookSyncPayload> {
        if (managedCover == null) return upsert(payload, clientOpId = null)
        val idStr = payload.id
        pendingManagedCovers[idStr] = managedCover
        return try {
            upsert(payload, clientOpId = null)
        } finally {
            pendingManagedCovers.remove(idStr)
        }
    }

    /**
     * Sets the managed-cover columns (provenance + relative path + sha256 hash) and bumps the
     * row's revision so the change propagates to clients via the sync bus.
     *
     * Opens its own transaction. The `SyncEvent.Updated` published to [ChangeBus]
     * carries the full aggregate so clients can refresh immediately.
     *
     * @return [AppResult.Success] on success;
     *   [AppResult.Failure] with [SyncError.NotFound] when [id] has no row.
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
            val rowsAffected =
                BookTable.update({ BookTable.id eq idStr }) { stmt ->
                    stmt[BookTable.coverSource] = source.name.lowercase()
                    stmt[BookTable.coverPath] = relPath
                    stmt[BookTable.coverHash] = hash
                    stmt[BookTable.revision] = rev
                    stmt[BookTable.updatedAt] = now
                }
            if (rowsAffected == 0) {
                AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = idStr))
            } else {
                val saved =
                    readPayload(idStr)
                        ?: error("readPayload returned null immediately after setManagedCover for $idStr")
                bus.publish(
                    repo = this@BookRepository,
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
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Nulls the managed-cover columns (`cover_source`, `cover_path`, `cover_hash`) and bumps
     * the row's revision so the change propagates to clients via the sync bus.
     *
     * Opens its own transaction. The `SyncEvent.Updated` published to [ChangeBus]
     * carries the full aggregate so clients can refresh immediately.
     *
     * @return [AppResult.Success] on success;
     *   [AppResult.Failure] with [SyncError.NotFound] when [id] has no row.
     */
    suspend fun clearManagedCover(id: BookId): AppResult<Unit> {
        val idStr = idAsString(id)
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            val rowsAffected =
                BookTable.update({ BookTable.id eq idStr }) { stmt ->
                    stmt[BookTable.coverSource] = null
                    stmt[BookTable.coverPath] = null
                    stmt[BookTable.coverHash] = null
                    stmt[BookTable.revision] = rev
                    stmt[BookTable.updatedAt] = now
                }
            if (rowsAffected == 0) {
                AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = idStr))
            } else {
                val saved =
                    readPayload(idStr)
                        ?: error("readPayload returned null immediately after clearManagedCover for $idStr")
                bus.publish(
                    repo = this@BookRepository,
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
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Resolves where [id]'s cover image lives for the cover-serving route. Delegates to
     * [ManagedCoverFiles.coverInfo] — see that method's KDoc for the full resolution contract.
     * Returns null when the book is absent or has no cover (plain 404 to the caller).
     *
     * Opens its own short read transaction — callable outside any substrate orchestration.
     */
    suspend fun coverInfo(id: BookId): CoverInfo? = managedCoverFiles.coverInfo(id)

    /**
     * Runs an FTS5 full-text search against `book_search` and returns matching
     * [BookId]s in rank order (best match first), capped at [limit] results.
     *
     * Joins `book_search_map` to translate FTS5 integer rowids back to the
     * string book ids this application uses. The search query is parameterised
     * so user-supplied strings never touch the SQL text. [limit] is clamped
     * by the caller ([BookServiceImpl]) before this method is invoked.
     *
     * When [accessFilter] is non-null its `SELECT b2.id …` subquery is spliced as
     * `AND m.book_id IN (<sql>)` so only ids the viewer can reach survive — the
     * caller ([BookServiceImpl.searchBooks]) derives it from
     * [BookAccessPolicy.accessibleBookIdsSql][com.calypsan.listenup.server.api.BookAccessPolicy.accessibleBookIdsSql],
     * which yields `null` for ROOT/ADMIN (unfiltered). Args are spliced in statement
     * order: `MATCH ?` → the access subquery's args → `LIMIT ?`.
     */
    suspend fun searchFts(
        query: String,
        limit: Int,
        accessFilter: SqlFragment? = null,
    ): List<BookId> =
        suspendTransaction(db) {
            val results = mutableListOf<BookId>()
            val accessClause = if (accessFilter != null) " AND m.book_id IN (${accessFilter.sql})" else ""
            val stmt =
                "SELECT m.book_id FROM book_search s " +
                    "JOIN book_search_map m ON s.rowid = m.rowid " +
                    "WHERE book_search MATCH ?" + accessClause + " ORDER BY rank LIMIT ?"
            val args =
                listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to query) +
                    (accessFilter?.args ?: emptyList()) +
                    listOf(IntegerColumnType() to limit)
            TransactionManager.current().exec(stmt = stmt, args = args) { rs ->
                while (rs.next()) results += BookId(rs.getString(1))
            }
            results
        }

    /** Resolves the natural key `(library_id, root_rel_path)` to a [BookId], or null. */
    private suspend fun findByPath(
        libraryId: LibraryId,
        rootRelPath: String,
    ): BookId? =
        suspendTransaction(db) {
            BookTable
                .selectAll()
                .where {
                    (BookTable.libraryId eq libraryId.value) and (BookTable.rootRelPath eq rootRelPath)
                }.firstOrNull()
                ?.get(BookTable.id)
                ?.let { BookId(it) }
        }

    /**
     * Resolves the move-detection key `(library_id, inode)` to a [BookId], or null.
     *
     * When two books share an inode (hardlinks), the first match by insertion
     * order is returned deterministically and a warning is logged — spec §5.3.
     */
    private suspend fun findByInode(
        libraryId: LibraryId,
        inode: Long,
    ): BookId? =
        suspendTransaction(db) {
            val matches =
                BookTable
                    .selectAll()
                    .where { (BookTable.libraryId eq libraryId.value) and (BookTable.inode eq inode) }
                    .map { it[BookTable.id] }
            if (matches.size > 1) {
                log.warn { "Multiple books share inode $inode in library ${libraryId.value}; picking first" }
            }
            matches.firstOrNull()?.let { BookId(it) }
        }

    /**
     * Writes the book's scalar fields to [stmt].
     *
     * [preserveCoverColumns] skips writing the three cover columns (source, path, hash) when
     * true — used by the sticky-upload merge in [writePayload] to protect a user-uploaded cover
     * from being clobbered on re-scan. For INSERT ([existed] = false) this is always false.
     *
     * [managedCover] supplies the cover provenance when a managed file was written at scan time.
     * When non-null (and [preserveCoverColumns] is false), the managed columns — source, path,
     * and hash — are written in the same statement, making the scan a single atomic write.
     * When null, legacy behaviour applies: source/hash come from [p]'s cover payload and
     * [BookTable.coverPath] is set to null (managed path is handled by [setManagedCover]).
     */
    private fun applyBookFields(
        stmt: UpdateBuilder<*>,
        p: BookSyncPayload,
        preserveCoverColumns: Boolean = false,
        managedCover: StoredCoverInfo? = null,
    ) {
        stmt[BookTable.title] = p.title
        stmt[BookTable.sortTitle] = p.sortTitle
        stmt[BookTable.subtitle] = p.subtitle
        stmt[BookTable.description] = p.description
        stmt[BookTable.publishYear] = p.publishYear
        stmt[BookTable.publisher] = p.publisher
        stmt[BookTable.language] = p.language
        stmt[BookTable.isbn] = p.isbn
        stmt[BookTable.asin] = p.asin
        stmt[BookTable.abridged] = p.abridged
        stmt[BookTable.explicit] = p.explicit
        stmt[BookTable.hasScanWarning] = p.hasScanWarning
        stmt[BookTable.totalDuration] = p.totalDuration
        if (!preserveCoverColumns) {
            if (managedCover != null) {
                // Single-write path: scan-time managed cover lands in the same statement.
                stmt[BookTable.coverSource] = managedCover.source.name.lowercase()
                stmt[BookTable.coverPath] = managedCover.relPath
                stmt[BookTable.coverHash] = managedCover.hash
            } else {
                stmt[BookTable.coverSource] =
                    p.cover
                        ?.source
                        ?.name
                        ?.lowercase()
                // coverPath is managed by setManagedCover (not set here) — the wire DTO
                // never carries a managed path for scan-produced payloads.
                stmt[BookTable.coverPath] = null
                stmt[BookTable.coverHash] = p.cover?.hash
            }
        }
        stmt[BookTable.rootRelPath] = p.rootRelPath
        stmt[BookTable.inode] = p.inode
        stmt[BookTable.scannedAt] = p.scannedAt
    }

    /**
     * Replaces this book's contributor junction rows. The caller MUST supply a
     * payload whose contributor ids already exist in [ContributorTable] —
     * `upsertFromAnalyzed` satisfies this by resolving every contributor through
     * [ContributorRepository.resolveOrCreate] before calling `upsert`.
     */
    private fun replaceContributors(
        bookId: String,
        contributors: List<BookContributorPayload>,
    ) {
        BookContributorTable.deleteWhere { BookContributorTable.bookId eq bookId }
        // Two display names can resolve to the same contributor id when sortName deduplication
        // collapses them (e.g. "Brandon Sanderson" and "B. Sanderson" both map to sortName
        // "Sanderson, Brandon" via an embedded authorsSort tag). The junction PK is
        // (book_id, contributor_id, role); inserting both rows would trigger
        // SQLITE_CONSTRAINT_PRIMARYKEY and abort the whole book ingest. Collapse to one row per
        // (contributor, role) — first occurrence wins for ordinal and creditedAs.
        contributors.distinctBy { it.id to it.role }.forEachIndexed { idx, c ->
            BookContributorTable.insert {
                it[BookContributorTable.bookId] = bookId
                it[BookContributorTable.contributorId] = c.id
                it[BookContributorTable.role] = c.role
                it[BookContributorTable.creditedAs] = c.creditedAs
                it[BookContributorTable.ordinal] = idx
            }
        }
    }

    /**
     * Replaces this book's series junction rows. The caller MUST supply a
     * payload whose series ids already exist in [BookSeriesTable] —
     * `upsertFromAnalyzed` satisfies this by resolving every series through
     * [SeriesRepository.resolveOrCreate] before calling `upsert`.
     */
    private fun replaceSeries(
        bookId: String,
        series: List<BookSeriesPayload>,
    ) {
        BookSeriesMembershipTable.deleteWhere { BookSeriesMembershipTable.bookId eq bookId }
        // A book can be tagged with the same series twice (sloppy "A;A" tags, or two spellings that
        // normalize to one series). Collapse to one membership per series — the junction PK is
        // (book_id, series_id) — keeping the first sequence. Without this, a duplicate aborts the
        // whole book ingest on the PK constraint.
        series.distinctBy { it.id }.forEachIndexed { idx, s ->
            BookSeriesMembershipTable.insert {
                it[BookSeriesMembershipTable.bookId] = bookId
                it[BookSeriesMembershipTable.seriesId] = s.id
                it[BookSeriesMembershipTable.sequence] = s.sequence
                it[BookSeriesMembershipTable.ordinal] = idx
            }
        }
    }

    private fun replaceChapters(
        bookId: String,
        chapters: List<BookChapterPayload>,
    ) {
        BookChapterTable.deleteWhere { BookChapterTable.bookId eq bookId }
        chapters.forEachIndexed { idx, ch ->
            BookChapterTable.insert {
                it[BookChapterTable.bookId] = bookId
                it[BookChapterTable.ordinal] = idx
                it[BookChapterTable.id] = ch.id.ifBlank { UUID.randomUUID().toString() }
                it[BookChapterTable.title] = ch.title
                it[BookChapterTable.duration] = ch.duration
                it[BookChapterTable.startTime] = ch.startTime
            }
        }
    }

    private fun replaceAudioFiles(
        bookId: String,
        files: List<BookAudioFilePayload>,
    ) {
        BookAudioFileTable.deleteWhere { BookAudioFileTable.bookId eq bookId }
        files.forEachIndexed { idx, f ->
            BookAudioFileTable.insert {
                it[BookAudioFileTable.bookId] = bookId
                it[BookAudioFileTable.ordinal] = idx
                it[BookAudioFileTable.id] = f.id.ifBlank { UUID.randomUUID().toString() }
                it[BookAudioFileTable.filename] = f.filename
                it[BookAudioFileTable.format] = f.format
                it[BookAudioFileTable.codec] = f.codec
                it[BookAudioFileTable.duration] = f.duration
                it[BookAudioFileTable.size] = f.size
            }
        }
    }

    /**
     * Returns the full book aggregates for every book that has a junction row for
     * [contributorId] in [BookContributorTable]. Results are ordered by book
     * [BookTable.createdAt] ascending (stable, scan-insertion order).
     *
     * Used by [com.calypsan.listenup.server.api.ContributorServiceImpl.listBooksByContributor].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findByContributor(contributorId: ContributorId): List<BookSyncPayload> =
        suspendTransaction(db) {
            val bookIds =
                BookContributorTable
                    .select(BookContributorTable.bookId)
                    .where { BookContributorTable.contributorId eq contributorId.value }
                    .map { it[BookContributorTable.bookId] }
            bookIds.mapNotNull { readPayload(it) }
        }

    /**
     * Returns the full book aggregates for every book that has a membership row for
     * [seriesId] in [BookSeriesMembershipTable]. Results are ordered by
     * [BookSeriesMembershipTable.ordinal] ascending (series-position order).
     *
     * Used by [com.calypsan.listenup.server.api.SeriesServiceImpl.listBooksBySeries].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findBySeries(seriesId: SeriesId): List<BookSyncPayload> =
        suspendTransaction(db) {
            val bookIds =
                BookSeriesMembershipTable
                    .select(BookSeriesMembershipTable.bookId)
                    .where { BookSeriesMembershipTable.seriesId eq seriesId.value }
                    .orderBy(BookSeriesMembershipTable.ordinal)
                    .map { it[BookSeriesMembershipTable.bookId] }
            bookIds.mapNotNull { readPayload(it) }
        }

    /**
     * Test-only accessor for the protected [idAsString]. Used by
     * `BookRepositoryIdAsStringTest` to verify the value-class unwrap.
     */
    internal fun idAsStringForTest(id: BookId): String = idAsString(id)

    /**
     * Test-only accessor for the protected [readPayload]. Used by
     * `BookRepositoryReadTest` to verify the aggregate-read shape directly,
     * outside the substrate's `upsert` / `pullSince` orchestration.
     */
    internal suspend fun readPayloadForTest(idStr: String): BookSyncPayload? = readPayload(idStr)
}

/**
 * Replaces the FTS row for [payload] in `book_search`, allocating or reusing
 * the integer rowid via [BookSearchMapTable].
 *
 * `book_search` is a `contentless_delete=1` FTS5 table (V9 migration), which
 * lets the inverted index drop a row's tokens via plain `DELETE FROM book_search
 * WHERE rowid = ?`. Without that flag, contentless FTS5 requires the special
 * `INSERT INTO ft(ft, rowid, ...) VALUES('delete', rowid, ...old values...)`
 * command — a workable but heavier path that this table deliberately avoids.
 *
 * The rowid is interpolated into the SQL string because it's an Int we just
 * computed (no injection surface). Text columns are parameterised through
 * Exposed's `exec(args = ...)` form. Must run inside an open transaction.
 */
private fun upsertFtsRow(payload: BookSyncPayload) {
    val rowid = resolveOrAllocateFtsRowid(payload.id)
    val contributorNames = payload.contributors.joinToString(", ") { it.name }
    val seriesNames = payload.series.joinToString(", ") { it.name }
    val tx = TransactionManager.current()
    tx.exec("DELETE FROM book_search WHERE rowid = $rowid")
    tx.exec(
        stmt =
            "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names) " +
                "VALUES ($rowid, ?, ?, ?, ?, ?)",
        args =
            listOf(
                TextColumnType() to payload.title,
                TextColumnType() to (payload.subtitle ?: ""),
                TextColumnType() to (payload.description ?: ""),
                TextColumnType() to contributorNames,
                TextColumnType() to seriesNames,
            ),
    )
}

private fun resolveOrAllocateFtsRowid(bookId: String): Int {
    val existing =
        BookSearchMapTable
            .selectAll()
            .where { BookSearchMapTable.bookId eq bookId }
            .firstOrNull()
    if (existing != null) return existing[BookSearchMapTable.rowid]
    val maxExpr = BookSearchMapTable.rowid.max()
    val nextRowid =
        (
            BookSearchMapTable
                .select(maxExpr)
                .firstOrNull()
                ?.get(maxExpr)
                ?: 0
        ) + 1
    BookSearchMapTable.insert {
        it[BookSearchMapTable.bookId] = bookId
        it[BookSearchMapTable.rowid] = nextRowid
    }
    return nextRowid
}

/**
 * Commits the `(inboxCollectionId, bookId)` membership for a newly-inserted book.
 *
 * Called from inside [BookRepository.writePayload]'s INSERT branch — already within the
 * book-insert transaction. [CollectionBookRepository.upsert] opens a `suspendTransaction`
 * that JOINS that transaction (Exposed coroutine txn reuse), so the membership lands
 * atomically with the book row; it is never a separate transaction.
 */
private suspend fun insertInboxMembership(
    collectionBookRepository: com.calypsan.listenup.server.sync.CollectionBookRepository,
    inboxCollectionId: String,
    bookId: String,
    now: Long,
) {
    collectionBookRepository.upsert(
        com.calypsan.listenup.api.sync.CollectionBookSyncPayload(
            collectionId = inboxCollectionId,
            bookId = bookId,
            createdAt = now,
            revision = 0L,
            deletedAt = null,
        ),
    )
}

/**
 * Resolves a single raw scanner genre [raw] for [bookIdStr] through the 3-step
 * cascade — curator alias → built-in normalization → pending. See
 * [BookRepository.processGenreStrings] for the full contract.
 *
 * Must be called inside a `suspendTransaction { }` block.
 */
private fun resolveGenreString(
    bookIdStr: String,
    raw: String,
    now: Long,
) {
    // 1. Custom curator alias (genre_aliases DB table) wins.
    val customGenreId = GenreAliasTable.resolve(raw)
    if (customGenreId != null) {
        BookGenreTable.insertIfAbsent(bookIdStr, customGenreId)
        return
    }

    // 2. Built-in normalization: raw -> canonical slug(s) -> live taxonomy genre id.
    var resolvedAny = false
    for (slug in GenreNormalizer.normalizeToSlugs(raw)) {
        val genreId = GenreTable.findBySlug(slug) ?: continue
        BookGenreTable.insertIfAbsent(bookIdStr, genreId)
        resolvedAny = true
    }

    // 3. Nothing matched -> queue for curator review.
    if (!resolvedAny) {
        PendingBookGenreTable.addPending(bookIdStr, raw, now)
    }
}
