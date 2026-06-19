package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.cover.StoredCoverInfo
import com.calypsan.listenup.server.db.BookAudioFileTable
import com.calypsan.listenup.server.db.BookChapterTable
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookTable
import java.util.UUID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Transaction-scoped helpers for writing the book-row and its four child tables.
 *
 * All methods issue Exposed DSL against the already-open transaction; they do NOT
 * call [com.calypsan.listenup.server.sync.nextRevision], the change bus, or any
 * outer `suspendTransaction { }` — those are the substrate's responsibility. The
 * caller ([BookRepository.writePayload]) provides the open transaction context.
 */
internal class BookAggregateWriter {
    /**
     * Writes the book's scalar fields to [stmt].
     *
     * [preserveCoverColumns] skips writing the three cover columns (source, path, hash) when
     * true — used by the sticky-upload merge in [BookRepository.writePayload] to protect a
     * user-uploaded cover from being clobbered on re-scan. For INSERT ([existed] = false)
     * this is always false.
     *
     * [managedCover] supplies the cover provenance when a managed file was written at scan time.
     * When non-null (and [preserveCoverColumns] is false), the managed columns — source, path,
     * and hash — are written in the same statement, making the scan a single atomic write.
     * When null, legacy behaviour applies: source/hash come from [p]'s cover payload and
     * [BookTable.coverPath] is set to null (managed path is handled by [BookRepository.setManagedCover]).
     */
    fun applyBookFields(
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
     * payload whose contributor ids already exist in [com.calypsan.listenup.server.db.ContributorTable] —
     * `upsertFromAnalyzed` satisfies this by resolving every contributor through
     * [ContributorRepository.resolveOrCreate] before calling `upsert`.
     */
    fun replaceContributors(
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
        val deduped = contributors.distinctBy { it.id to it.role }
        BookContributorTable.batchInsert(
            deduped.withIndex().toList(),
            shouldReturnGeneratedValues = false,
        ) { (idx, c) ->
            this[BookContributorTable.bookId] = bookId
            this[BookContributorTable.contributorId] = c.id
            this[BookContributorTable.role] = c.role
            this[BookContributorTable.creditedAs] = c.creditedAs
            this[BookContributorTable.ordinal] = idx
        }
    }

    /**
     * Replaces this book's series junction rows. The caller MUST supply a
     * payload whose series ids already exist in [com.calypsan.listenup.server.db.BookSeriesTable] —
     * `upsertFromAnalyzed` satisfies this by resolving every series through
     * [SeriesRepository.resolveOrCreate] before calling `upsert`.
     */
    fun replaceSeries(
        bookId: String,
        series: List<BookSeriesPayload>,
    ) {
        BookSeriesMembershipTable.deleteWhere { BookSeriesMembershipTable.bookId eq bookId }
        // A book can be tagged with the same series twice (sloppy "A;A" tags, or two spellings that
        // normalize to one series). Collapse to one membership per series — the junction PK is
        // (book_id, series_id) — keeping the first sequence. Without this, a duplicate aborts the
        // whole book ingest on the PK constraint.
        val deduped = series.distinctBy { it.id }
        BookSeriesMembershipTable.batchInsert(
            deduped.withIndex().toList(),
            shouldReturnGeneratedValues = false,
        ) { (idx, s) ->
            this[BookSeriesMembershipTable.bookId] = bookId
            this[BookSeriesMembershipTable.seriesId] = s.id
            this[BookSeriesMembershipTable.sequence] = s.sequence
            this[BookSeriesMembershipTable.ordinal] = idx
        }
    }

    fun replaceChapters(
        bookId: String,
        chapters: List<BookChapterPayload>,
    ) {
        BookChapterTable.deleteWhere { BookChapterTable.bookId eq bookId }
        BookChapterTable.batchInsert(chapters.withIndex().toList(), shouldReturnGeneratedValues = false) { (idx, ch) ->
            this[BookChapterTable.bookId] = bookId
            this[BookChapterTable.ordinal] = idx
            this[BookChapterTable.id] = ch.id.ifBlank { UUID.randomUUID().toString() }
            this[BookChapterTable.title] = ch.title
            this[BookChapterTable.duration] = ch.duration
            this[BookChapterTable.startTime] = ch.startTime
        }
    }

    fun replaceAudioFiles(
        bookId: String,
        files: List<BookAudioFilePayload>,
    ) {
        BookAudioFileTable.deleteWhere { BookAudioFileTable.bookId eq bookId }
        BookAudioFileTable.batchInsert(files.withIndex().toList(), shouldReturnGeneratedValues = false) { (idx, f) ->
            this[BookAudioFileTable.bookId] = bookId
            this[BookAudioFileTable.ordinal] = idx
            this[BookAudioFileTable.id] = f.id.ifBlank { UUID.randomUUID().toString() }
            this[BookAudioFileTable.filename] = f.filename
            this[BookAudioFileTable.format] = f.format
            this[BookAudioFileTable.codec] = f.codec
            this[BookAudioFileTable.duration] = f.duration
            this[BookAudioFileTable.size] = f.size
        }
    }
}
