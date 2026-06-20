package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction as exposedSuspendTransaction

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
 * **Why service-layer and not a SQL trigger?** The spec chose application-layer
 * reindexing for testability and decoupling — a trigger cannot join across
 * `book_tags → tags → book_search_map` cleanly, whereas this class does it in
 * readable, testable Kotlin.
 *
 * **Two database handles during the cutover.** [db] (the SQLDelight [ListenUpDatabase])
 * backs the `book_search` FTS write path — [reindexBook] reads its source columns,
 * tag names and genre names through generated SQLDelight queries and writes the FTS row
 * with `deleteFtsRow` + `insertFtsRow`. [exposedDb] (the Exposed [Database] over the same
 * WAL file) still backs the two surfaces this unit (U3b) deliberately left on Exposed:
 *   - the `contributor_search` FTS write in [reindexContributorAliases] — a *different*
 *     FTS table, out of U3b's `book_search` scope; it ports with the contributor FTS work;
 *   - the affected-book enumeration in the `reindexAllBooksFor*` sweeps, which reads the
 *     Exposed table objects ([com.calypsan.listenup.server.db.BookContributorTable] etc.,
 *     KEPT per the cutover plan). Each sweep still funnels the per-book FTS write through
 *     [reindexBook], so the `book_search` write itself is always SQLDelight.
 * Both handles read/write the one underlying file; in WAL mode cross-engine reads are safe.
 */
class BookSearchReindexer(
    private val bookTagRepository: BookTagRepository,
    private val tagRepository: TagRepository,
    private val db: ListenUpDatabase,
    private val exposedDb: Database,
) {
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
    suspend fun reindexBook(bookId: String) {
        // Resolve live tag names for this book (through the already-SQLDelight repos).
        val junctions = bookTagRepository.findAllForBook(bookId)
        val tagNames =
            junctions.mapNotNull { junc ->
                tagRepository.findById(junc.tagId)?.name
            }
        val tagsValue = tagNames.joinToString(" ")

        suspendTransaction<Unit>(db) {
            // Resolve the FTS5 rowid via book_search_map. If absent, nothing to do.
            val rowid =
                db.bookSearchQueries.selectRowidForBook(bookId).executeAsOneOrNull()
                    ?: return@suspendTransaction

            // Read the existing FTS content from the source tables so we can re-insert
            // all columns — FTS5 contentless tables don't store the original text.
            val existing = db.bookSearchQueries.selectFtsSourceByRowid(rowid).executeAsOneOrNull()

            // Live genre names via book_genres JOIN genres; tombstoned genres are filtered
            // by the query (g.deleted_at IS NULL), name-ordered. Space-joined for FTS5 tokens.
            val genresValue =
                db.bookGenresQueries
                    .genreNamesForBook(bookId)
                    .executeAsList()
                    .joinToString(" ")

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
    }

    /**
     * Reindexes every book that currently has a live junction row for [tagId].
     * Called after [com.calypsan.listenup.server.api.TagServiceImpl.renameTag]
     * and [com.calypsan.listenup.server.api.TagServiceImpl.deleteTag].
     */
    suspend fun reindexAllBooksForTag(tagId: String) {
        val bookIds = bookTagRepository.findBookIdsForTag(tagId)
        for (bookId in bookIds) {
            reindexBook(bookId)
        }
    }

    /**
     * Reindexes every book that currently has a live junction row referencing
     * [contributorId]. Called by
     * [com.calypsan.listenup.server.api.ContributorServiceImpl.updateContributor]
     * (on name / sortName change) and `deleteContributor` (after junction hard-delete).
     *
     * Reads affected book IDs from [com.calypsan.listenup.server.db.BookContributorTable].
     * Each per-book reindex re-pulls `contributor_names` from source via [reindexBook].
     */
    suspend fun reindexAllBooksForContributor(contributorId: String) {
        val bookIds =
            exposedSuspendTransaction(exposedDb) {
                com.calypsan.listenup.server.db.BookContributorTable
                    .bookIdsForContributor(contributorId)
            }
        for (bookId in bookIds) {
            reindexBook(bookId)
        }
    }

    /**
     * Mirror for series — reindexes every book that currently has a live
     * junction row referencing [seriesId].
     */
    suspend fun reindexAllBooksForSeries(seriesId: String) {
        val bookIds =
            exposedSuspendTransaction(exposedDb) {
                com.calypsan.listenup.server.db.BookSeriesMembershipTable
                    .bookIdsForSeries(seriesId)
            }
        for (bookId in bookIds) {
            reindexBook(bookId)
        }
    }

    /**
     * Reindexes every book that currently has a live junction row referencing
     * [genreId]. Called by [com.calypsan.listenup.server.api.GenreServiceImpl]
     * after `updateGenre` (on name change), `deleteGenre`, `mergeGenres`, and
     * `mapUnmappedToGenre` — operations that change either the genre's display
     * name or the set of books linked to it.
     *
     * Reads affected book IDs from [com.calypsan.listenup.server.db.BookGenreTable].
     * Each per-book reindex re-pulls `genres` from source via [reindexBook].
     */
    suspend fun reindexAllBooksForGenre(genreId: String) {
        val bookIds =
            exposedSuspendTransaction(exposedDb) {
                com.calypsan.listenup.server.db.BookGenreTable
                    .bookIdsForGenre(genreId)
            }
        for (bookId in bookIds) {
            reindexBook(bookId)
        }
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
            exposedSuspendTransaction(exposedDb) {
                com.calypsan.listenup.server.db.BookGenreTable
                    .booksForGenrePrefix(pathPrefix, Int.MAX_VALUE)
            }
        for (bookId in bookIds) {
            reindexBook(bookId)
        }
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
     * This method reads the live alias rows via
     * [com.calypsan.listenup.server.db.ContributorAliasTable.aliasesFor] together
     * with the contributor's current `name`/`sort_name`/`description`, then
     * DELETE + re-INSERTs the FTS row to refresh `aliases`. The other columns are
     * re-read from the source so the FTS row stays internally consistent even if a
     * trigger update has not yet been observed.
     *
     * **Stays on Exposed (U3b):** `contributor_search` is a *different* FTS table from
     * `book_search` — outside this unit's scope. It ports alongside the contributor FTS
     * work; until then it reads/writes [exposedDb] over the shared WAL.
     *
     * Called by
     * [com.calypsan.listenup.server.api.ContributorServiceImpl.mergeContributors]
     * and `unmergeContributor` after the alias set changes. Safe to call when the
     * contributor row does not exist — the rowid lookup returns null and the call
     * silently no-ops.
     */
    suspend fun reindexContributorAliases(contributorId: String) {
        exposedSuspendTransaction(exposedDb) {
            val tx = TransactionManager.current()

            // contributor_search is contentless and shares rowids with contributors
            // via the V22 triggers (which use new.rowid / old.rowid). Resolve the
            // contributor's SQLite rowid; absent → no FTS row to refresh.
            val source = readContributorSource(tx, contributorId) ?: return@exposedSuspendTransaction
            val aliasesValue =
                com.calypsan.listenup.server.db.ContributorAliasTable
                    .aliasesFor(contributorId)
                    .joinToString(" ")

            // FTS5 contentless_delete=1: DELETE drops old tokens, then re-INSERT replaces the row.
            tx.exec("DELETE FROM contributor_search WHERE rowid = ${source.rowid}")
            tx.exec(
                stmt =
                    "INSERT INTO contributor_search(rowid, name, sort_name, description, aliases) " +
                        "VALUES (?, ?, ?, ?, ?)",
                args =
                    listOf(
                        IntegerColumnType() to source.rowid,
                        TextColumnType() to source.name,
                        TextColumnType() to source.sortName,
                        TextColumnType() to source.description,
                        TextColumnType() to aliasesValue,
                    ),
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private data class ContributorFtsSource(
        val rowid: Int,
        val name: String,
        val sortName: String,
        val description: String,
    )

    /**
     * Reads the contributor's SQLite rowid + the trigger-managed FTS columns
     * (`name`, `sort_name`, `description`) from the source `contributors` table.
     * Returns null when the contributor row does not exist.
     */
    private fun readContributorSource(
        tx: org.jetbrains.exposed.v1.jdbc.JdbcTransaction,
        contributorId: String,
    ): ContributorFtsSource? {
        var result: ContributorFtsSource? = null
        tx.exec(
            stmt =
                "SELECT rowid, name, COALESCE(sort_name, '') AS sort_name, " +
                    "COALESCE(description, '') AS description " +
                    "FROM contributors WHERE id = ?",
            args = listOf(TextColumnType() to contributorId),
        ) { rs ->
            if (rs.next()) {
                result =
                    ContributorFtsSource(
                        rowid = rs.getInt("rowid"),
                        name = rs.getString("name") ?: "",
                        sortName = rs.getString("sort_name") ?: "",
                        description = rs.getString("description") ?: "",
                    )
            }
        }
        return result
    }
}
