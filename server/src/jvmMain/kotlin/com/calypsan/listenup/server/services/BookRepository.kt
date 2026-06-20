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
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverInfo
import com.calypsan.listenup.server.cover.ManagedCoverFiles
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import com.calypsan.listenup.server.sync.nextRevision
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
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
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
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
) : SyncableRepository<BookSyncPayload, BookId>(
        db = db,
        table = BookTable,
        bus = bus,
        registry = registry,
        domainName = "books",
        clock = clock,
    ),
    BookIngestPort,
    BookRevisionTouch {
    /** Cover file and path helpers — file I/O and path resolution outside the sync seam. */
    private val managedCoverFiles = ManagedCoverFiles(coverImageStore, homeDir, db)

    /** Book-row + child-table write mechanics (transaction-scoped, no revision/bus calls). */
    private val bookAggregateWriter = BookAggregateWriter()

    /** Read query helpers — FTS, path/inode lookup, and contributor/series joins. */
    private val bookFinder = BookFinder(db)

    /** Genre junction write helpers — `book_genres` and auto-create for unknown strings (no revision/bus). */
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

    override val elementSerializer: KSerializer<BookSyncPayload> = BookSyncPayload.serializer()

    override fun idAsString(id: BookId): String = id.value

    override val BookSyncPayload.id: BookId
        get() = BookId(this.id)

    override fun BookSyncPayload.revisionOf(): Long = revision

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

        return assembleBookPayload(bookRow, contributors, series, genres, audioFiles, chapters)
    }

    /** Batched hydration: fetches each child table once per id-chunk and assembles in input-id order. */
    override suspend fun readPayloads(idStrs: List<String>): List<BookSyncPayload> = readBookPayloads(idStrs)

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
        // Read per-call extras injected via the coroutine context by upsertFromAnalyzed.
        // Null for all other callers — same semantics as before, but scoped to the call, not the map.
        val extras = coroutineContext[BookWriteExtras.Key]
        val managedCover = extras?.managedCover

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
                bookAggregateWriter.applyBookFields(
                    stmt,
                    value,
                    preserveCoverColumns = isUploadedLocked,
                    managedCover = managedCover,
                )
                stmt[BookTable.revision] = rev
                stmt[BookTable.updatedAt] = now
                stmt[BookTable.deletedAt] = null
                stmt[BookTable.clientOpId] = clientOpId
                // Edit-path only: re-stamp the added date. `applyBookFields` never writes
                // createdAt, so a rescan's placeholder value stays ignored — only an explicit
                // metadata edit (which sets this override) can move it.
                extras?.createdAtOverride?.let { stmt[BookTable.createdAt] = it }
            }
        } else {
            BookTable.insert { stmt ->
                stmt[BookTable.id] = value.id
                // libraryId + folderId come from the payload; the legacy registry
                // was the Books-A single-library resolver and is no longer the
                // source of truth here.
                stmt[BookTable.libraryId] = value.libraryId.value
                stmt[BookTable.folderId] = value.folderId.value
                bookAggregateWriter.applyBookFields(
                    stmt,
                    value,
                    preserveCoverColumns = false,
                    managedCover = managedCover,
                )
                stmt[BookTable.revision] = rev
                stmt[BookTable.createdAt] = now
                stmt[BookTable.updatedAt] = now
                stmt[BookTable.deletedAt] = null
                stmt[BookTable.clientOpId] = clientOpId
            }
            // Atomic system-collection membership: insertSystemMembership's upsert joins THIS transaction.
            // A stashed system collection id without a wired repo is a misconfiguration — fail loudly
            // rather than silently drop the membership (which would re-open the firehose leak for
            // held books, or leave non-held books uncollected and invisible to members).
            extras?.systemCollectionId?.let { sysId ->
                val repo =
                    requireNotNull(collectionBookRepository) {
                        "system-collection membership requested but CollectionBookRepository is not wired"
                    }
                insertSystemMembership(repo, sysId, value.id, now)
            }
        }

        bookAggregateWriter.replaceContributors(value.id, value.contributors)
        bookAggregateWriter.replaceSeries(value.id, value.series)
        bookAggregateWriter.replaceChapters(value.id, value.chapters)
        bookAggregateWriter.replaceAudioFiles(value.id, value.audioFiles)
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
    ): AppResult<IngestOutcome> {
        val rootRelPath = analyzed.candidate.rootRelPath

        bookFinder.findByPath(libraryId, rootRelPath)?.let { existing ->
            return upsertFromAnalyzed(existing, libraryId, folderId, analyzed, pendingCover, systemCollectionId)
                .map { IngestOutcome(existing, wasNew = false) }
        }

        analyzed.candidate.files
            .firstOrNull()
            ?.inode
            ?.let { inode ->
                bookFinder.findByInode(libraryId, inode)?.let { existing ->
                    val previousPath = findById(existing)?.rootRelPath
                    log.info { "Book moved: $previousPath → $rootRelPath" }
                    return upsertFromAnalyzed(existing, libraryId, folderId, analyzed, pendingCover, systemCollectionId)
                        .map { IngestOutcome(existing, wasNew = false) }
                }
            }

        val newId = BookId(UUID.randomUUID().toString())
        return upsertFromAnalyzed(newId, libraryId, folderId, analyzed, pendingCover, systemCollectionId)
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
    suspend fun softDeleteAbsent(
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
     * Tombstones every non-deleted book in [libraryId] whose `rootRelPath` is not
     * in [seenPaths] — the path-keyed counterpart to [softDeleteAbsent].
     *
     * **Full-scan only.** Same authoritativity contract as [softDeleteAbsent]:
     * call this only after a complete library walk; incremental scans must not call it.
     *
     * Using `rootRelPath` avoids the need to resolve a [BookId] for every
     * unchanged book during a re-scan — the path-set is built cheaply from the
     * scan result without any DB round-trips.
     */
    override suspend fun softDeleteAbsentByPaths(
        libraryId: LibraryId,
        seenPaths: Set<String>,
    ) {
        val toDelete =
            suspendTransaction(db) {
                BookTable
                    .selectAll()
                    .where {
                        (BookTable.libraryId eq libraryId.value) and BookTable.deletedAt.isNull()
                    }.map { it[BookTable.id] to it[BookTable.rootRelPath] }
                    .filterNot { (_, path) -> path in seenPaths }
                    .map { (id, _) -> id }
            }
        for (id in toDelete) {
            softDelete(BookId(id), clientOpId = null)
        }
    }

    /**
     * Soft-deletes the live book at [rootRelPath] inside [libraryId], if one exists.
     *
     * Idempotent: a no-op when no live (non-deleted) book exists at that path.
     * When a book is found it is removed through [softDelete], which bumps the
     * revision and emits [com.calypsan.listenup.api.sync.SyncEvent.Deleted] on the
     * change bus — clients reflow exactly as they would for any other delete.
     *
     * Called by [BookPersister] on a [com.calypsan.listenup.api.dto.scanner.ChangeEventDto.Removed]
     * event from an incremental scan, where the full-scan tombstone sweep does not run.
     */
    override suspend fun softDeleteByPath(
        libraryId: LibraryId,
        rootRelPath: String,
    ) {
        val id =
            suspendTransaction(db) {
                BookTable
                    .selectAll()
                    .where {
                        (BookTable.libraryId eq libraryId.value) and
                            (BookTable.rootRelPath eq rootRelPath) and
                            BookTable.deletedAt.isNull()
                    }.firstOrNull()
                    ?.let { BookId(it[BookTable.id]) }
            } ?: return // already gone — idempotent no-op
        softDelete(id, clientOpId = null)
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
        systemCollectionId: String? = null,
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
        // sticky-UPLOADED skip, and the only-on-create system-collection membership gate.
        val existing = findById(bookId)
        val isNew = existing == null
        // The cover must be treated as unchanged for the idempotency check when:
        //  • the existing cover is UPLOADED (sticky — we never overwrite it on re-scan regardless), OR
        //  • the SHA-256 of the incoming cover bytes matches the stored hash (byte-identical artwork).
        // Any other case (new artwork, cover removed, cover added) means the write must land.
        val pendingCoverHash = pendingCover?.bytes?.sha256Hex()
        val coverUnchanged =
            existing?.cover?.source == CoverSource.UPLOADED ||
                pendingCoverHash == existing?.cover?.hash
        val result: AppResult<BookSyncPayload> =
            if (existing != null && coverUnchanged && payload.matchesStoredContent(existing)) {
                // Idempotent re-scan: content is identical to what's stored, so skip the
                // revision-bumping upsert AND the cover file-write. resolveOrInsert already
                // returned this bookId, so the tombstone sweep still sees the book.
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
                withContext(
                    BookWriteExtras(
                        managedCover = storedCover,
                        systemCollectionId = if (isNew) systemCollectionId else null,
                    ),
                ) {
                    upsert(payload, clientOpId = null)
                }
            }
        if (result is AppResult.Success) {
            val now = clock.now().toEpochMilliseconds()
            suspendTransaction(db) { bookGenreWriter.processGenreStrings(bookId, analyzed.genres, now) }
            bookTagWriter?.writeScanTags(bookId, analyzed.tags)
        }
        return result
    }

    /**
     * True when [this] freshly-scanned payload matches the [stored] aggregate in every content field.
     *
     * The scanned payload carries placeholder server-assigned fields (`revision`/`updatedAt`/`createdAt`
     * = 0, `scannedAt` = now), a `null` cover (the cover is stored + written separately), and empty
     * `genres` (genres are reconciled separately by [bookGenreWriter]). Those are normalized to the
     * stored values before comparing, so the result reflects only real content changes. A match means
     * the re-scan changed nothing — the revision must NOT bump (otherwise every scan makes the client
     * re-pull every book).
     *
     * Audio-file and chapter rows carry server-generated UUIDs at rest but are produced with `id = ""`
     * by the mapper (the server assigns IDs on first write). To make the comparison id-stable, both
     * sides drop the `id` field from audio files and chapters before comparing — structural equality of
     * every other field is sufficient to detect a real content change.
     *
     * Cover is NOT included in the fields compared here because the scanned payload always carries
     * `cover = null`. The caller gates the shortcut separately via [sha256Hex] comparison of the
     * pending cover bytes against the stored hash, so a cover-only change (new embedded artwork in
     * otherwise unchanged audio metadata) is still detected.
     *
     * Genre-only changes are still written by [bookGenreWriter] on both branches (idempotent) but
     * do NOT bump the book revision on their own — an acceptable trade-off versus the full-resync
     * storm this check prevents.
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
     * scanner (curator alias → [GenreNormalizer] → pending). Idempotent —
     * wipes the book's prior `book_genres`/`pending_book_genres` first. Writes the junction only; it
     * does NOT bump the book's revision or publish — pair it with a book `upsert` (which re-reads the
     * junction and emits) so the change propagates. The match-apply wizard calls this immediately
     * before its text upsert.
     */
    suspend fun setBookGenres(
        bookId: BookId,
        rawGenres: List<String>,
    ): AppResult<Unit> = bookGenreWriter.setBookGenres(bookId, rawGenres)

    /**
     * Reads the full book aggregate for [id], or null when absent. Opens its own
     * read transaction — usable outside the substrate's `upsert`/`pullSince`
     * orchestration (the scanner reads a book's current `rootRelPath` before
     * logging a move; tests assert post-write state).
     */
    suspend fun findById(id: BookId): BookSyncPayload? = suspendTransaction(db) { readPayload(id.value) }

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
     * Bumps the row's revision (and `updatedAt`) without touching any content column, so a
     * visibility-only change — collection membership add/remove — re-enters every member's
     * incremental `revision > cursor AND <accessible>` pull and newly-visible books reach them.
     *
     * Opens its own transaction. The `SyncEvent.Updated` published to [ChangeBus]
     * carries the full aggregate so clients refresh immediately. Mirrors
     * [setManagedCover] minus the cover-column writes.
     *
     * @return [AppResult.Success] on success;
     *   [AppResult.Failure] with [SyncError.NotFound] when [id] has no row.
     */
    override suspend fun touchRevision(id: BookId): AppResult<Unit> {
        val idStr = idAsString(id)
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            val rowsAffected =
                BookTable.update({ BookTable.id eq idStr }) { stmt ->
                    stmt[BookTable.revision] = rev
                    stmt[BookTable.updatedAt] = now
                }
            if (rowsAffected == 0) {
                AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = idStr))
            } else {
                val saved =
                    readPayload(idStr)
                        ?: error("readPayload returned null immediately after touchRevision for $idStr")
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
    ): List<BookId> = bookFinder.searchFts(query, limit, accessFilter)

    /**
     * Returns the full book aggregates for every book that has a junction row for
     * [contributorId] in [BookContributorTable]. Results are ordered by book
     * [BookTable.createdAt] ascending (stable, scan-insertion order).
     *
     * Used by [com.calypsan.listenup.server.api.ContributorServiceImpl.listBooksByContributor].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findByContributor(contributorId: ContributorId): List<BookSyncPayload> =
        bookFinder.findByContributor(contributorId)

    /**
     * Returns the full book aggregates for every book that has a membership row for
     * [seriesId] in [BookSeriesMembershipTable]. Results are ordered by
     * [BookSeriesMembershipTable.ordinal] ascending (series-position order).
     *
     * Used by [com.calypsan.listenup.server.api.SeriesServiceImpl.listBooksBySeries].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findBySeries(seriesId: SeriesId): List<BookSyncPayload> = bookFinder.findBySeries(seriesId)

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

    /** Test-only accessor for the protected [readPayloads]. */
    internal suspend fun readPayloadsForTest(idStrs: List<String>): List<BookSyncPayload> = readPayloads(idStrs)
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
 * Commits the `(systemCollectionId, bookId)` membership for a newly-inserted book.
 *
 * Called from inside [BookRepository.writePayload]'s INSERT branch — already within the
 * book-insert transaction. [CollectionBookRepository.upsert] opens a `suspendTransaction`
 * that JOINS that transaction (Exposed coroutine txn reuse), so the membership lands
 * atomically with the book row; it is never a separate transaction.
 *
 * [systemCollectionId] is either the library's ALL_BOOKS collection id (inbox gate off)
 * or its INBOX collection id (inbox gate on). The two cases are mutually exclusive and
 * are resolved by [com.calypsan.listenup.server.services.BookPersister.resolveSystemCollectionId]
 * before this is called.
 */
private suspend fun insertSystemMembership(
    collectionBookRepository: com.calypsan.listenup.server.sync.CollectionBookRepository,
    systemCollectionId: String,
    bookId: String,
    now: Long,
) {
    collectionBookRepository.upsert(
        com.calypsan.listenup.api.sync.CollectionBookSyncPayload(
            collectionId = systemCollectionId,
            bookId = bookId,
            createdAt = now,
            revision = 0L,
            deletedAt = null,
        ),
    )
}

/** Returns the SHA-256 hex digest of [this] byte array — matches [ImageStore]'s hash algorithm. */
private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { "%02x".format(it) }
}
