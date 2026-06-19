package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.sync.BookSearchReindexer
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Admin-triggered full rebuild of all FTS5 indexes. Recovery path for index drift after a
 * failed migration.
 *
 * The four FTS5 tables fall into two families that demand different rebuild idioms:
 *
 *  - **Contentless** (`content=''`, `contentless_delete=1`): `book_search` (V9/V23) and
 *    `contributor_search` (switched to contentless in V22 to carry the synthetic `aliases`
 *    column). These do **not** support the native `'rebuild'` command — it raises
 *    `SQLITE_ERROR ('rebuild' may not be used with a contentless fts5 table)`. They are
 *    rebuilt from source: `book_search` via [BookSearchReindexer.reindexBook] per book;
 *    `contributor_search` via a DELETE + re-INSERT from `contributors` + `contributor_aliases`,
 *    mirroring the V22 `contributors_au` trigger.
 *  - **External-content** (`content='book_series'` / `content='tags'`): `series_search` and
 *    `tag_search` support the native `INSERT INTO <t>(<t>) VALUES('rebuild')` command.
 */
class SearchReindexService(
    private val db: Database,
    private val reindexer: BookSearchReindexer,
) {
    /**
     * Rebuilds all FTS5 indexes across all four families. The rebuild spans multiple
     * transactions (one per book for `book_search`, then a single transaction for the
     * contributor/series/tag families) and does NOT provide cross-family atomicity — a
     * crash mid-run leaves some indexes fresh and others stale. The operation is
     * idempotent, so re-invoking completes partial progress.
     *
     * @return number of books reindexed via [BookSearchReindexer].
     */
    suspend fun reindexAll(): Int {
        val bookIds =
            suspendTransaction(db) {
                BookTable
                    .selectAll()
                    .where { BookTable.deletedAt.isNull() }
                    .map { it[BookTable.id] }
            }
        for (id in bookIds) reindexer.reindexBook(id)
        suspendTransaction(db) {
            val tx = TransactionManager.current()
            // contributor_search is contentless (V22): rebuild from source, mirroring the
            // contributors_au trigger (server/src/jvmMain/resources/db/migration/V22__contributor_aliases.sql)
            // so name/sort_name/description AND aliases are restored. If the trigger's
            // alias-sourcing changes in that migration, this rebuild SQL must change too.
            tx.exec("DELETE FROM contributor_search")
            tx.exec(
                "INSERT INTO contributor_search(rowid, name, sort_name, description, aliases) " +
                    "SELECT c.rowid, c.name, c.sort_name, c.description, " +
                    "COALESCE((SELECT GROUP_CONCAT(a.alias, ' ') FROM contributor_aliases a " +
                    "WHERE a.contributor_id = c.id), '') " +
                    "FROM contributors c",
            )
            // series_search and tag_search are external-content: the native 'rebuild' applies.
            tx.exec("INSERT INTO series_search(series_search) VALUES('rebuild')")
            tx.exec("INSERT INTO tag_search(tag_search) VALUES('rebuild')")
        }
        return bookIds.size
    }
}
