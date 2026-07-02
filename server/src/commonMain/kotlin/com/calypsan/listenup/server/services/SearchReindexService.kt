package com.calypsan.listenup.server.services

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.BookSearchReindexer

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
    private val db: ListenUpDatabase,
    private val driver: SqlDriver,
    private val reindexer: BookSearchReindexer,
) {
    /**
     * Rebuilds all FTS5 indexes across all four families. The rebuild spans multiple
     * transactions (one per 200-book chunk for `book_search`, then a single transaction
     * for the contributor/series/tag families) and does NOT provide cross-family
     * atomicity — a crash mid-run leaves some indexes fresh and others stale. The
     * operation is idempotent, so re-invoking completes partial progress.
     *
     * @return number of books reindexed via [BookSearchReindexer].
     */
    suspend fun reindexAll(): Int {
        val bookIds =
            suspendTransaction(db) {
                db.booksQueries.selectAllLiveIds().executeAsList()
            }
        reindexer.reindexBooks(bookIds)
        suspendTransaction<Unit>(db) {
            // contributor_search is contentless (V22): rebuild from source, mirroring the
            // contributors_au trigger (server/src/jvmMain/resources/db/migration/V22__contributor_aliases.sql)
            // so name/sort_name/description AND aliases are restored. If the trigger's
            // alias-sourcing changes in that migration, this rebuild SQL must change too.
            driver.execute(
                identifier = null,
                sql = "DELETE FROM contributor_search",
                parameters = 0,
            )
            driver.execute(
                identifier = null,
                sql =
                    "INSERT INTO contributor_search(rowid, name, sort_name, description, aliases) " +
                        "SELECT c.rowid, c.name, c.sort_name, c.description, " +
                        "COALESCE((SELECT GROUP_CONCAT(a.alias, ' ') FROM contributor_aliases a " +
                        "WHERE a.contributor_id = c.id), '') " +
                        "FROM contributors c",
                parameters = 0,
            )
            // series_search and tag_search are external-content: the native 'rebuild' applies.
            driver.execute(
                identifier = null,
                sql = "INSERT INTO series_search(series_search) VALUES('rebuild')",
                parameters = 0,
            )
            driver.execute(
                identifier = null,
                sql = "INSERT INTO tag_search(tag_search) VALUES('rebuild')",
                parameters = 0,
            )
        }
        return bookIds.size
    }
}
