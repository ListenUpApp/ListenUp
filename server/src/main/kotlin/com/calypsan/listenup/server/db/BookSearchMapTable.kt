package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Server-internal mapping between [BookTable.id] (UUID strings) and `book_search`'s
 * INTEGER rowid (an FTS5 constraint — rowids in virtual tables must be integers).
 *
 * Each book gets a stable rowid on first FTS write, reused on subsequent updates.
 * The mapping is opaque to clients — `BookService.searchBooks` returns BookIds,
 * never rowids. Not a [com.calypsan.listenup.server.sync.SyncableTable]: this is
 * an index-internal lookup, not a syncable aggregate.
 *
 * Rowid allocation happens explicitly in `BookRepository.writePayload` via
 * `MAX(rowid) + 1` rather than relying on Exposed's `autoIncrement()` — SQLite's
 * implicit-rowid alias only kicks in for `INTEGER PRIMARY KEY` columns, and here
 * the PK is `book_id` (TEXT).
 */
internal object BookSearchMapTable : Table("book_search_map") {
    val bookId = varchar("book_id", 36)
    val rowid = integer("rowid")
    override val primaryKey = PrimaryKey(bookId)

    init {
        uniqueIndex("idx_book_search_map_rowid", rowid)
    }
}
