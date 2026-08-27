package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookDocumentPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import kotlin.uuid.Uuid

/**
 * Transaction-scoped helpers for writing the book's four child tables over the generated
 * SQLDelight queries.
 *
 * All methods issue child-table queries against the already-open SQLDelight transaction;
 * they do NOT bump the global revision, publish to the change bus, or open their own
 * transaction — those are the substrate's responsibility. The caller
 * ([BookRepository.writePayload]) provides the open transaction context.
 *
 * Each `replace*` is delete-by-book then insert each row, preserving the prior dedup
 * (contributors by `(id, role)`, series by `id`) and the `ordinal` ordering.
 */
internal class BookAggregateWriter(
    private val db: ListenUpDatabase,
) {
    /**
     * Replaces this book's contributor junction rows. The caller MUST supply a
     * payload whose contributor ids already exist in `contributors` —
     * `upsertFromAnalyzed` satisfies this by resolving every contributor through
     * [ContributorRepository.resolveOrCreate] before calling `upsert`.
     */
    fun replaceContributors(
        bookId: String,
        contributors: List<BookContributorPayload>,
    ) {
        db.bookContributorsQueries.deleteByBookId(bookId)
        // Two display names can resolve to the same contributor id when sortName deduplication
        // collapses them (e.g. "Brandon Sanderson" and "B. Sanderson" both map to sortName
        // "Sanderson, Brandon" via an embedded authorsSort tag). The junction PK is
        // (book_id, contributor_id, role); inserting both rows would trigger
        // SQLITE_CONSTRAINT_PRIMARYKEY and abort the whole book ingest. Collapse to one row per
        // (contributor, role) — first occurrence wins for ordinal and creditedAs.
        val deduped = contributors.distinctBy { it.id to it.role }
        deduped.forEachIndexed { idx, c ->
            db.bookContributorsQueries.insert(
                book_id = bookId,
                contributor_id = c.id,
                role = c.role,
                credited_as = c.creditedAs,
                ordinal = idx.toLong(),
            )
        }
    }

    /**
     * Replaces this book's series junction rows. The caller MUST supply a
     * payload whose series ids already exist in `book_series` —
     * `upsertFromAnalyzed` satisfies this by resolving every series through
     * [SeriesRepository.resolveOrCreate] before calling `upsert`.
     */
    fun replaceSeries(
        bookId: String,
        series: List<BookSeriesPayload>,
    ) {
        db.bookSeriesMembershipsQueries.deleteByBookId(bookId)
        // A book can be tagged with the same series twice (sloppy "A;A" tags, or two spellings that
        // normalize to one series). Collapse to one membership per series — the junction PK is
        // (book_id, series_id) — keeping the first sequence. Without this, a duplicate aborts the
        // whole book ingest on the PK constraint.
        val deduped = series.distinctBy { it.id }
        deduped.forEachIndexed { idx, s ->
            db.bookSeriesMembershipsQueries.insert(
                book_id = bookId,
                series_id = s.id,
                sequence = s.sequence,
                ordinal = idx.toLong(),
            )
        }
    }

    fun replaceChapters(
        bookId: String,
        chapters: List<BookChapterPayload>,
    ) {
        db.bookChaptersQueries.deleteByBookId(bookId)
        chapters.forEachIndexed { idx, ch ->
            db.bookChaptersQueries.insert(
                book_id = bookId,
                ordinal = idx.toLong(),
                id = ch.id.ifBlank { Uuid.random().toString() },
                title = ch.title,
                duration = ch.duration,
                start_time = ch.startTime,
                part_title = ch.partTitle,
                book_title = ch.bookTitle,
            )
        }
    }

    fun replaceAudioFiles(
        bookId: String,
        files: List<BookAudioFilePayload>,
    ) {
        db.bookAudioFilesQueries.deleteByBookId(bookId)
        files.forEachIndexed { idx, f ->
            db.bookAudioFilesQueries.insert(
                book_id = bookId,
                ordinal = idx.toLong(),
                id = f.id.ifBlank { Uuid.random().toString() },
                filename = f.filename,
                format = f.format,
                codec = f.codec,
                duration = f.duration,
                size = f.size,
                codecProfile = f.codecProfile,
                spatial = f.spatial,
                bitrate = f.bitrate?.toLong(),
                sampleRate = f.sampleRate?.toLong(),
                channels = f.channels?.toLong(),
            )
        }
    }

    fun replaceDocuments(
        bookId: String,
        documents: List<BookDocumentPayload>,
    ) {
        db.bookDocumentsQueries.deleteByBookId(bookId)
        documents.forEachIndexed { idx, d ->
            db.bookDocumentsQueries.insert(
                book_id = bookId,
                ordinal = idx.toLong(),
                id = d.id.ifBlank { Uuid.random().toString() },
                filename = d.filename,
                format = d.format,
                size = d.size,
                hash = d.hash,
            )
        }
    }
}
