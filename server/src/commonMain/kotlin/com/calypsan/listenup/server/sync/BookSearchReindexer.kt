package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * Service-layer reindexer for the `book_search` FTS5 table.
 *
 * `book_search` is a contentless FTS5 table with `contentless_delete=1` (see V9/V21
 * migrations). The application layer owns all FTS population — no triggers keep
 * `book_search` in sync. [BookSearchReindexer] is the sole writer for the entire
 * FTS5 row (title, subtitle, description, contributor_names, series_names, tags,
 * genres).
 *
 * Each call reads the live (non-tombstoned) state for the target book from all
 * source tables, then uses the FTS5 `DELETE` + re-insert idiom required by
 * `contentless_delete=1` tables.
 *
 * **Why service-layer and not a SQL trigger?** Application-layer reindexing
 * wins on testability and decoupling — a trigger cannot join across
 * `book_tags → tags → book_search_map` cleanly, whereas this class does it in
 * readable, testable Kotlin.
 */
class BookSearchReindexer(
    private val bookTagRepository: BookTagRepository,
    private val tagRepository: TagRepository,
    private val db: ListenUpDatabase,
    private val driver: SqlDriver,
) {
    /**
     * Recomputes the FTS row for one book with its tag and genre names pre-resolved.
     * Must be called inside an open [suspendTransaction] — uses only synchronous
     * generated queries. Silently no-ops when the book has no book_search_map row.
     */
    private fun reindexBookInOpenTransaction(
        bookId: String,
        tagsValue: String,
        genresValue: String,
    ) {
        val rowid =
            db.bookSearchQueries.selectRowidForBook(bookId).executeAsOneOrNull()
                ?: return

        // Read the existing FTS content from the source tables so we can re-insert
        // all columns — FTS5 contentless tables don't store the original text.
        val existing = db.bookSearchQueries.selectFtsSourceByRowid(rowid).executeAsOneOrNull()

        // FTS5 contentless_delete=1: delete old tokens first, then re-insert full 8-col row.
        db.bookSearchQueries.deleteFtsRow(rowid)
        db.bookSearchQueries.insertFtsRow(
            rowid = rowid,
            title = existing?.title ?: "",
            subtitle = existing?.subtitle ?: "",
            description = existing?.description ?: "",
            contributor_names = existing?.contributor_names ?: "",
            series_names = existing?.series_names ?: "",
            tags = tagsValue,
            genres = genresValue,
        )
    }

    /**
     * Recomputes the `book_search` FTS5 rows for every id in [bookIds].
     *
     * Tag and genre names are resolved in batched reads up front (tag junctions via
     * [BookTagRepository.findAllForBooks], tag names via [TagRepository.findByIds],
     * genre names via [db.bookGenresQueries.genreNamesByBookIds]) — repository calls
     * open their own transactions, so they must complete BEFORE the write
     * transaction opens (nested [suspendTransaction] calls can land on a different
     * connection and deadlock against the single writer). The delete+re-insert
     * pairs then run in one transaction per [txChunkSize]-book chunk. Books without
     * a `book_search_map` row are silently skipped, matching [reindexBook]. A
     * mid-run failure rolls back only the current chunk; previously committed
     * chunks remain — the operation is idempotent, re-run to complete.
     */
    suspend fun reindexBooks(
        bookIds: List<String>,
        txChunkSize: Int = REINDEX_TX_CHUNK,
    ) {
        if (bookIds.isEmpty()) return
        val junctionsByBook = bookTagRepository.findAllForBooks(bookIds)
        val tagIds =
            junctionsByBook.values
                .flatten()
                .map { it.tagId }
                .distinct()
        val tagNameById = tagRepository.findByIds(tagIds).associate { it.id to it.name }
        // Genre names batched up front for the same reason as tags: the read must
        // complete before the write transaction opens (nested suspendTransaction
        // deadlocks against the single writer).
        val genreNamesByBook: Map<String, List<String>> =
            suspendTransaction(db) {
                bookIds
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk ->
                        db.bookGenresQueries.genreNamesByBookIds(chunk).executeAsList()
                    }.groupBy({ it.book_id }, { it.name })
            }
        for (chunk in bookIds.chunked(txChunkSize)) {
            suspendTransaction<Unit>(db) {
                for (bookId in chunk) {
                    val tagsValue =
                        junctionsByBook[bookId]
                            .orEmpty()
                            .mapNotNull { tagNameById[it.tagId] }
                            .joinToString(" ")
                    val genresValue = genreNamesByBook[bookId].orEmpty().joinToString(" ")
                    reindexBookInOpenTransaction(bookId, tagsValue, genresValue)
                }
            }
        }
    }

    /**
     * Recomputes the `book_search` FTS5 row for [bookId] from the live source tables.
     *
     * `book_search` is a contentless FTS5 table with `contentless_delete=1` (V9/V21);
     * the application layer owns all FTS population. This method reads the current state
     * from `books`, `book_contributors → contributors`, `book_series_memberships →
     * book_series`, and `book_tags → tags`, then DELETE + re-INSERTs the FTS row (the
     * FTS5 idiom for `contentless_delete=1`).
     *
     * Safe to call when the book has no `book_search_map` row (never scanned, or
     * tombstoned) — the rowid lookup returns null and the call silently no-ops.
     */
    suspend fun reindexBook(bookId: String) = reindexBooks(listOf(bookId))

    /**
     * Reindexes every book that currently has a live junction row for [tagId].
     * Called after [com.calypsan.listenup.server.api.TagServiceImpl.renameTag]
     * and [com.calypsan.listenup.server.api.TagServiceImpl.deleteTag].
     */
    suspend fun reindexAllBooksForTag(tagId: String) {
        val bookIds = bookTagRepository.findBookIdsForTag(tagId)
        reindexBooks(bookIds)
    }

    /**
     * Reindexes every book that currently has a live junction row referencing
     * [contributorId]. Called by
     * [com.calypsan.listenup.server.api.ContributorServiceImpl.updateContributor]
     * (on name / sortName change) and `deleteContributor` (after junction hard-delete).
     */
    suspend fun reindexAllBooksForContributor(contributorId: String) {
        val bookIds =
            suspendTransaction(db) {
                db.bookContributorsQueries
                    .bookIdsForContributor(contributorId)
                    .executeAsList()
            }
        reindexBooks(bookIds)
    }

    /**
     * Mirror for series — reindexes every book that currently has a live
     * junction row referencing [seriesId].
     */
    suspend fun reindexAllBooksForSeries(seriesId: String) {
        val bookIds =
            suspendTransaction(db) {
                db.bookSeriesMembershipsQueries
                    .bookIdsForSeries(seriesId)
                    .executeAsList()
            }
        reindexBooks(bookIds)
    }

    /**
     * Reindexes every book that currently has a live junction row referencing
     * [genreId]. Called by [com.calypsan.listenup.server.api.GenreServiceImpl]
     * after `updateGenre` (on name change), `deleteGenre`, `mergeGenres`, and
     * `mapUnmappedToGenre` — operations that change either the genre's display
     * name or the set of books linked to it.
     */
    suspend fun reindexAllBooksForGenre(genreId: String) {
        val bookIds =
            suspendTransaction(db) {
                db.bookGenresQueries
                    .bookIdsForGenre(genreId)
                    .executeAsList()
            }
        reindexBooks(bookIds)
    }

    /**
     * Reindexes every book linked to any live genre whose materialized path
     * equals [pathPrefix] or starts with `pathPrefix + "/"`. Called by
     * `moveGenre` (subtree reparent) — the descendant rows' paths changed but
     * each book's junction set did not; per-book reindex refreshes display.
     *
     * Uses the same `/fic` vs `/fiction` collision-safe predicate as
     * [com.calypsan.listenup.server.db.BookGenreTable.booksForGenrePrefix]
     * (`g.path = ? OR g.path LIKE ? || '/%'`).
     */
    suspend fun reindexAllBooksForSubtree(pathPrefix: String) {
        val bookIds =
            suspendTransaction(db) {
                db.bookGenresQueries
                    .booksForGenrePrefix(pathPrefix, Int.MAX_VALUE.toLong())
                    .executeAsList()
            }
        reindexBooks(bookIds)
    }

    /**
     * Refreshes the `contributor_search.aliases` FTS5 column for [contributorId].
     *
     * `contributor_search` is contentless FTS5 (V22). The AI/AU/AD triggers keep
     * `name`/`sort_name`/`description` in sync with the `contributors` row, but the
     * `aliases` column is *not* derived from any single source column — it is the
     * space-separated denormalisation of `contributor_aliases.alias` for the
     * contributor — and is therefore the application's responsibility to write.
     *
     * This method reads the live alias rows via [db.contributorsQueries.aliasesFor]
     * together with the contributor's current `name`/`sort_name`/`description`, then
     * DELETE + re-INSERTs the FTS row to refresh `aliases`. The other columns are
     * re-read from the source so the FTS row stays internally consistent even if a
     * trigger update has not yet been observed.
     *
     * Called by
     * [com.calypsan.listenup.server.api.ContributorServiceImpl.mergeContributors]
     * and `unmergeContributor` after the alias set changes. Safe to call when the
     * contributor row does not exist — the rowid lookup returns null and the call
     * silently no-ops.
     */
    suspend fun reindexContributorAliases(contributorId: String) {
        suspendTransaction<Unit>(db) {
            // contributor_search is contentless and shares rowids with contributors
            // via the V22 triggers (which use new.rowid / old.rowid). Resolve the
            // contributor's SQLite rowid + FTS columns from the source table.
            val source = readContributorSource(contributorId) ?: return@suspendTransaction

            val aliasesValue =
                db.contributorsQueries
                    .aliasesFor(contributorId)
                    .executeAsList()
                    .joinToString(" ")

            // FTS5 contentless_delete=1: DELETE drops old tokens, then re-INSERT replaces the row.
            driver.execute(
                identifier = null,
                sql = "DELETE FROM contributor_search WHERE rowid = ?",
                parameters = 1,
                binders = { bindLong(0, source.rowid) },
            )
            driver.execute(
                identifier = null,
                sql =
                    "INSERT INTO contributor_search(rowid, name, sort_name, description, aliases) " +
                        "VALUES (?, ?, ?, ?, ?)",
                parameters = 5,
                binders = {
                    bindLong(0, source.rowid)
                    bindString(1, source.name)
                    bindString(2, source.sortName)
                    bindString(3, source.description)
                    bindString(4, aliasesValue)
                },
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private data class ContributorFtsSource(
        val rowid: Long,
        val name: String,
        val sortName: String,
        val description: String,
    )

    /**
     * Reads the contributor's SQLite rowid + the trigger-managed FTS columns
     * (`name`, `sort_name`, `description`) from the source `contributors` table.
     * Returns null when the contributor row does not exist.
     *
     * Must be called inside an active [suspendTransaction] — runs on the same
     * connection as the surrounding transaction.
     */
    private fun readContributorSource(contributorId: String): ContributorFtsSource? =
        driver
            .executeQuery(
                identifier = null,
                sql =
                    "SELECT rowid, name, COALESCE(sort_name, '') AS sort_name, " +
                        "COALESCE(description, '') AS description " +
                        "FROM contributors WHERE id = ?",
                mapper = { cursor ->
                    QueryResult.Value(
                        if (cursor.next().value) {
                            ContributorFtsSource(
                                rowid = cursor.getLong(0)!!,
                                name = cursor.getString(1) ?: "",
                                sortName = cursor.getString(2) ?: "",
                                description = cursor.getString(3) ?: "",
                            )
                        } else {
                            null
                        },
                    )
                },
                parameters = 1,
                binders = { bindString(0, contributorId) },
            ).value

    private companion object {
        /**
         * Books per write transaction in [reindexBooks]. Large enough that a bulk
         * reindex costs a handful of commits; small enough that one chunk's
         * delete+insert churn doesn't monopolize SQLite's single write connection.
         */
        const val REINDEX_TX_CHUNK = 200

        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}
