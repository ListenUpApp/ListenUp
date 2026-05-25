package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
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
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.cover.CoverInfo
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
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
 * Books-A is single-library. The repository takes a [libraryRegistry] rather
 * than a resolved [LibraryId] because the only source of that id —
 * `LibraryRegistry.currentLibrary()` — is a `suspend` function (it does a DB
 * read), and Koin `single { }` definitions cannot suspend. The library id is
 * therefore resolved lazily inside the `suspend` write path; the registry
 * caches the result after the first call, so the per-write cost is negligible.
 *
 * @param libraryRegistry resolves the single library id for this process; the
 *   INSERT branch of [writePayload] reads it to stamp a fresh book's
 *   `library_id` column.
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
    private val libraryRegistry: LibraryRegistry,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    clock: Clock = Clock.System,
) : SyncableRepository<BookSyncPayload, BookId>(
        db = db,
        table = BookTable,
        bus = bus,
        registry = registry,
        domainName = "books",
        clock = clock,
    ),
    BookIngestPort {
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
                CoverPayload(
                    source = CoverSource.valueOf(bookRow[BookTable.coverSource]!!.uppercase()),
                    hash = hash,
                )
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
        if (existed) {
            BookTable.update({ BookTable.id eq value.id }) { stmt ->
                applyBookFields(stmt, value)
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
                applyBookFields(stmt, value)
                stmt[BookTable.revision] = rev
                stmt[BookTable.createdAt] = now
                stmt[BookTable.updatedAt] = now
                stmt[BookTable.deletedAt] = null
                stmt[BookTable.clientOpId] = clientOpId
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
     * @return [AppResult.Success] carrying the stable [BookId] for this book —
     *   newly minted or pre-existing — only when the aggregate write landed.
     *   An [AppResult.Failure] means [upsertFromAnalyzed] did not persist the
     *   book; callers must not treat the failure as a persisted aggregate, and
     *   for a new book the minted UUID points at nothing.
     */
    override suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
    ): AppResult<BookId> {
        val rootRelPath = analyzed.candidate.rootRelPath

        findByPath(libraryId, rootRelPath)?.let { existing ->
            return upsertFromAnalyzed(existing, libraryId, folderId, analyzed).map { existing }
        }

        analyzed.candidate.files
            .firstOrNull()
            ?.inode
            ?.let { inode ->
                findByInode(libraryId, inode)?.let { existing ->
                    val previousPath = findById(existing)?.rootRelPath
                    log.info { "Book moved: $previousPath → $rootRelPath" }
                    return upsertFromAnalyzed(existing, libraryId, folderId, analyzed).map { existing }
                }
            }

        val newId = BookId(UUID.randomUUID().toString())
        return upsertFromAnalyzed(newId, libraryId, folderId, analyzed).map { newId }
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
     * `cover` is left null — cover hashing is a later task; the substrate-owned
     * `revision`/`updatedAt`/`createdAt` placeholders are overwritten by `upsert`.
     */
    suspend fun upsertFromAnalyzed(
        bookId: BookId,
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
    ): AppResult<BookSyncPayload> {
        val candidate = analyzed.candidate
        val inode = candidate.files.firstOrNull()?.inode
        val totalDuration = analyzed.embedded?.durationMs ?: 0L
        val resolvedContributors =
            buildContributors(analyzed).map { c ->
                c.copy(id = contributorRepository.resolveOrCreate(c.name).value)
            }
        val resolvedSeries =
            buildSeries(analyzed).map { s ->
                s.copy(id = seriesRepository.resolveOrCreate(s.name).value)
            }
        val payload =
            BookSyncPayload(
                id = bookId.value,
                libraryId = libraryId,
                folderId = folderId,
                title = analyzed.title,
                sortTitle = null,
                subtitle = analyzed.subtitle,
                description = analyzed.description,
                publishYear = analyzed.publishedYear,
                publisher = analyzed.publisher,
                language = analyzed.language,
                isbn = analyzed.isbn,
                asin = analyzed.asin,
                abridged = analyzed.abridged ?: false,
                explicit = analyzed.explicit ?: false,
                hasScanWarning = analyzed.hasScanWarning,
                totalDuration = totalDuration,
                cover = null,
                rootRelPath = candidate.rootRelPath,
                inode = inode,
                scannedAt = clock.now().toEpochMilliseconds(),
                contributors = resolvedContributors,
                series = resolvedSeries,
                audioFiles = buildAudioFiles(analyzed),
                chapters = buildChapters(analyzed),
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )
        return upsert(payload, clientOpId = null)
    }

    /**
     * Reads the full book aggregate for [id], or null when absent. Opens its own
     * read transaction — usable outside the substrate's `upsert`/`pullSince`
     * orchestration (the scanner reads a book's current `rootRelPath` before
     * logging a move; tests assert post-write state).
     */
    suspend fun findById(id: BookId): BookSyncPayload? = suspendTransaction(db) { readPayload(id.value) }

    /**
     * Resolves where [id]'s cover image lives, as an absolute filesystem path,
     * for the cover-serving route. Returns null when the book is absent **or**
     * has no cover (a null `coverSource`) — both are a plain 404 to the caller,
     * not an error.
     *
     * The persisted `cover_path` column is always null — the wire DTO carries
     * no path — so the path is *derived* here from the library root and the
     * book's `root_rel_path`:
     *
     *  - [CoverSource.FILESYSTEM] → the book directory is scanned for a cover
     *    image, mirroring the scanner's `Analyzer.resolveCover` precedence:
     *    a `cover.*` file (case-insensitive) wins, else the first image file
     *    by name. A [CoverInfo.Filesystem] is returned only when such a file
     *    actually exists on disk; otherwise null (the image was deleted since
     *    the scan — a 404, not a 500).
     *  - [CoverSource.EMBEDDED] → the book's primary audio file (lowest
     *    `ordinal`) resolves to `<root>/<rootRelPath>/<filename>`, returned as
     *    [CoverInfo.Embedded] for serve-time artwork extraction.
     *
     * Opens its own short read transaction — the route calls it outside any
     * substrate orchestration.
     */
    suspend fun coverInfo(id: BookId): CoverInfo? {
        val resolved =
            suspendTransaction(db) {
                val bookRow =
                    BookTable
                        .selectAll()
                        .where { BookTable.id eq id.value }
                        .firstOrNull() ?: return@suspendTransaction null
                val source = bookRow[BookTable.coverSource] ?: return@suspendTransaction null
                val rootRelPath = bookRow[BookTable.rootRelPath]
                // Resolve the folder root path via the book's folder_id column.
                // TODO: surface a typed error (LIB-C) if the folder row is missing.
                val folderRoot =
                    LibraryFolderTable
                        .selectAll()
                        .where { LibraryFolderTable.id eq bookRow[BookTable.folderId] }
                        .firstOrNull()
                        ?.get(LibraryFolderTable.rootPath)
                        ?: return@suspendTransaction null
                val primaryFilename =
                    BookAudioFileTable
                        .selectAll()
                        .where { BookAudioFileTable.bookId eq id.value }
                        .orderBy(BookAudioFileTable.ordinal)
                        .firstOrNull()
                        ?.get(BookAudioFileTable.filename)
                ResolvedCover(source, folderRoot, rootRelPath, primaryFilename)
            } ?: return null

        val bookDir = Path.of(resolved.libraryRoot, resolved.rootRelPath)
        val source =
            CoverSource.entries.firstOrNull { it.name.equals(resolved.source, ignoreCase = true) }
                ?: return null
        return when (source) {
            CoverSource.FILESYSTEM -> {
                resolveFilesystemCover(bookDir)?.let(CoverInfo::Filesystem)
            }

            CoverSource.EMBEDDED -> {
                resolved.primaryFilename
                    ?.let { bookDir.resolve(it) }
                    ?.takeIf { withContext(Dispatchers.IO) { Files.isRegularFile(it) } }
                    ?.let(CoverInfo::Embedded)
            }
        }
    }

    /**
     * Finds the filesystem cover image in [bookDir], mirroring the scanner's
     * `Analyzer.resolveCover` precedence: a file whose stem is `cover`
     * (case-insensitive) wins, else the first image file by name. Returns null
     * when the directory holds no image — or has vanished since the scan.
     */
    private suspend fun resolveFilesystemCover(bookDir: Path): Path? =
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(bookDir)) return@withContext null
            val images =
                Files
                    .list(bookDir)
                    .use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) }
                            .filter {
                                it.fileName
                                    .toString()
                                    .substringAfterLast('.', "")
                                    .lowercase() in IMAGE_EXTENSIONS
                            }.sorted(compareBy { it.fileName.toString() })
                            .toList()
                    }
            images.firstOrNull {
                it.fileName
                    .toString()
                    .substringBeforeLast('.')
                    .equals("cover", ignoreCase = true)
            }
                ?: images.firstOrNull()
        }

    /** Intermediate carrier for the columns [coverInfo] reads inside its transaction. */
    private data class ResolvedCover(
        val source: String,
        val libraryRoot: String,
        val rootRelPath: String,
        val primaryFilename: String?,
    )

    /**
     * Runs an FTS5 full-text search against `book_search` and returns matching
     * [BookId]s in rank order (best match first), capped at [limit] results.
     *
     * Joins `book_search_map` to translate FTS5 integer rowids back to the
     * string book ids this application uses. The search query is parameterised
     * so user-supplied strings never touch the SQL text. [limit] is clamped
     * by the caller ([BookServiceImpl]) before this method is invoked.
     */
    suspend fun searchFts(
        query: String,
        limit: Int,
    ): List<BookId> =
        suspendTransaction(db) {
            val results = mutableListOf<BookId>()
            val stmt =
                "SELECT m.book_id FROM book_search s " +
                    "JOIN book_search_map m ON s.rowid = m.rowid " +
                    "WHERE book_search MATCH ? ORDER BY rank LIMIT ?"
            TransactionManager.current().exec(
                stmt = stmt,
                args =
                    listOf(
                        TextColumnType() to query,
                        IntegerColumnType() to limit,
                    ),
            ) { rs ->
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

    // --- AnalyzedBook → BookSyncPayload mapping ------------------------------

    private fun buildContributors(analyzed: AnalyzedBook): List<BookContributorPayload> =
        analyzed.authors.map { contributorPayload(it, role = "author") } +
            analyzed.narrators.map { contributorPayload(it, role = "narrator") }

    private fun contributorPayload(
        name: String,
        role: String,
    ): BookContributorPayload =
        BookContributorPayload(
            id = "",
            name = name,
            sortName = null,
            role = role,
            creditedAs = null,
        )

    private fun buildSeries(analyzed: AnalyzedBook): List<BookSeriesPayload> =
        analyzed.series.map { entry ->
            BookSeriesPayload(id = "", name = entry.name, sequence = entry.sequence)
        }

    private fun buildAudioFiles(analyzed: AnalyzedBook): List<BookAudioFilePayload> {
        // Only the primary (first) audio file has an authoritative duration —
        // see the duration caveat on `upsertFromAnalyzed`.
        val primaryDuration = analyzed.embedded?.durationMs ?: 0L
        return analyzed.tracks.mapIndexed { index, track ->
            BookAudioFilePayload(
                id = "",
                index = index,
                filename = track.file.name,
                format = track.file.ext,
                codec = "",
                duration = if (index == 0) primaryDuration else 0L,
                size = track.file.size,
            )
        }
    }

    private fun buildChapters(analyzed: AnalyzedBook): List<BookChapterPayload> =
        analyzed.chapters.map { chapter ->
            BookChapterPayload(
                id = "",
                title = chapter.title,
                duration = chapter.endMs - chapter.startMs,
                startTime = chapter.startMs,
            )
        }

    private fun applyBookFields(
        stmt: UpdateBuilder<*>,
        p: BookSyncPayload,
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
        stmt[BookTable.coverSource] =
            p.cover
                ?.source
                ?.name
                ?.lowercase()
        // coverPath is server-derived from the filesystem at serve time;
        // the wire DTO doesn't carry it.
        stmt[BookTable.coverPath] = null
        stmt[BookTable.coverHash] = p.cover?.hash
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
        contributors.forEachIndexed { idx, c ->
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
        series.forEachIndexed { idx, s ->
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
     * Exposed's `exec(args = ...)` form.
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

    private companion object {
        /** Image file extensions the scanner recognises — see `FileTypeRules.imageExt`. */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
