package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/**
 * Transaction-scoped FTS write mechanics for the books aggregate, over the generated SQLDelight
 * queries.
 *
 * [upsertFtsRow] issues its `book_search` / `book_search_map` writes against the already-open
 * SQLDelight transaction; it does NOT bump the global revision, publish to the change bus, or open
 * its own transaction — the caller ([BookRepository.writePayload]) provides the open transaction
 * context. The caller supplies [db] (the SQLDelight handle) for the generated FTS queries.
 *
 * @param db the SQLDelight database backing the `book_search` + `book_search_map` writes.
 */
internal class BookFtsWriter(
    private val db: ListenUpDatabase,
) {
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
    fun upsertFtsRow(payload: BookSyncPayload) {
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
}
