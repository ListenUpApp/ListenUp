package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.server.db.BookAudioFileTable
import com.calypsan.listenup.server.db.BookChapterTable
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import java.util.UUID
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update

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
 */
class BookRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<BookSyncPayload, BookId>(
        db = db,
        table = BookTable,
        bus = bus,
        registry = registry,
        domainName = "books",
        clock = clock,
    ) {
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
     * Contributors and series resolve through their top-level catalogues by
     * normalized name — a book authored by "Brandon Sanderson" and one by
     * "  brandon  sanderson  " share a single `contributors` row. The wire-side
     * `id` is honoured when present; otherwise a fresh UUID is allocated for
     * new catalogue entries.
     */
    override suspend fun writePayload(
        value: BookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
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
                stmt[BookTable.libraryId] = libraryIdFor(value)
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

    /**
     * Resolves the library id for a fresh book insert.
     *
     * Books-A bootstraps with a single library row keyed off `LISTENUP_LIBRARY_PATH`
     * (see `LibraryTable`). Until Task 13 wires a `LibraryRegistry` that injects
     * the resolved id, the repository reads the lone library row directly. The
     * fail-fast `error` is a deliberate floor — the scanner and ingest tests
     * always seed a library row before exercising book writes.
     */
    private fun libraryIdFor(
        @Suppress("UNUSED_PARAMETER") value: BookSyncPayload,
    ): String =
        LibraryTable
            .selectAll()
            .limit(1)
            .firstOrNull()
            ?.get(LibraryTable.id)
            ?: error(
                "No library bootstrapped — Task 13 (LibraryRegistry) wires this properly; " +
                    "for now, ensure a row in 'libraries' before upserting books.",
            )

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

    private fun replaceContributors(
        bookId: String,
        contributors: List<BookContributorPayload>,
    ) {
        BookContributorTable.deleteWhere { BookContributorTable.bookId eq bookId }
        contributors.forEachIndexed { idx, c ->
            val contribId = ensureContributor(c)
            BookContributorTable.insert {
                it[BookContributorTable.bookId] = bookId
                it[BookContributorTable.contributorId] = contribId
                it[BookContributorTable.role] = c.role
                it[BookContributorTable.creditedAs] = c.creditedAs
                it[BookContributorTable.ordinal] = idx
            }
        }
    }

    private fun ensureContributor(c: BookContributorPayload): String {
        val normalized = c.name.normalizeForDedup()
        val existing =
            ContributorTable
                .selectAll()
                .where { ContributorTable.normalizedName eq normalized }
                .firstOrNull()
        if (existing != null) return existing[ContributorTable.id]
        val newId = c.id.ifBlank { UUID.randomUUID().toString() }
        ContributorTable.insert {
            it[ContributorTable.id] = newId
            it[ContributorTable.normalizedName] = normalized
            it[ContributorTable.name] = c.name
            it[ContributorTable.sortName] = c.sortName
        }
        return newId
    }

    private fun replaceSeries(
        bookId: String,
        series: List<BookSeriesPayload>,
    ) {
        BookSeriesMembershipTable.deleteWhere { BookSeriesMembershipTable.bookId eq bookId }
        series.forEachIndexed { idx, s ->
            val seriesId = ensureSeries(s)
            BookSeriesMembershipTable.insert {
                it[BookSeriesMembershipTable.bookId] = bookId
                it[BookSeriesMembershipTable.seriesId] = seriesId
                it[BookSeriesMembershipTable.sequence] = s.sequence
                it[BookSeriesMembershipTable.ordinal] = idx
            }
        }
    }

    private fun ensureSeries(s: BookSeriesPayload): String {
        val normalized = s.name.normalizeForDedup()
        val existing =
            BookSeriesTable
                .selectAll()
                .where { BookSeriesTable.normalizedName eq normalized }
                .firstOrNull()
        if (existing != null) return existing[BookSeriesTable.id]
        val newId = s.id.ifBlank { UUID.randomUUID().toString() }
        BookSeriesTable.insert {
            it[BookSeriesTable.id] = newId
            it[BookSeriesTable.normalizedName] = normalized
            it[BookSeriesTable.name] = s.name
            it[BookSeriesTable.sortName] = null
        }
        return newId
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

    private fun String.normalizeForDedup(): String = lowercase().trim().replace(Regex("\\s+"), " ")

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
