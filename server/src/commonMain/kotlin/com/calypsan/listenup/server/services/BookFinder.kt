package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.bindRaw
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/** SQLite caps a statement at 999 bound parameters; chunk `IN (…)` lists below it with headroom. */
private const val SQLITE_IN_CHUNK = 900

/**
 * Transaction-scoped read helpers for the books aggregate, over the generated SQLDelight
 * queries.
 *
 * Each method opens its own short read transaction — they do NOT bump the global revision,
 * the change bus, or any write query. The caller ([BookRepository]) provides [db] (the
 * SQLDelight handle) and [driver] (the shared [SqlDriver] behind it, used only for the
 * access-filtered FTS read whose spliced subquery is a runtime-built [SqlFragment] no static
 * SQLDelight query can express). The raw query runs inside the [suspendTransaction] on the same
 * connection, engine-neutral.
 *
 * @param db the SQLDelight database for the generated reads + aggregate hydration.
 * @param driver the SQLDelight driver used only by the dynamic access-filtered [searchFts] path.
 */
internal class BookFinder(
    private val db: ListenUpDatabase,
    private val driver: SqlDriver,
) {
    /**
     * Runs an FTS5 full-text search against `book_search` and returns matching
     * [BookId]s in rank order (best match first), capped at [limit] results.
     *
     * The unfiltered path uses the generated `searchBookIds` query (the static
     * MATCH+join+rank shape). When [accessFilter] is non-null its `SELECT b2.id …`
     * subquery must be spliced as `AND m.book_id IN (<sql>)` — a runtime-built fragment
     * that no static query can express — so that path runs the read engine-neutrally over the
     * shared SQLDelight [driver] inside the [suspendTransaction]. **Args are bound in statement
     * order and the order is security-relevant:** `MATCH ?` (the fts query) first, then the
     * access subquery's args, then `LIMIT ?` — a wrong order could bind the query into the
     * access subquery and leak inaccessible matches.
     *
     * The caller ([BookServiceImpl.searchBooks]) derives [accessFilter] from
     * [BookAccessPolicy.accessibleBookIdsSql][com.calypsan.listenup.server.api.BookAccessPolicy.accessibleBookIdsSql],
     * which yields `null` for ROOT/ADMIN (unfiltered). [limit] is clamped by the caller.
     */
    suspend fun searchFts(
        query: String,
        limit: Int,
        accessFilter: SqlFragment? = null,
    ): List<BookId> =
        if (accessFilter == null) {
            suspendTransaction(db) {
                db.bookSearchQueries
                    .searchBookIds(query, limit.toLong())
                    .executeAsList()
                    .map { BookId(it) }
            }
        } else {
            suspendTransaction(db) {
                val sql =
                    "SELECT m.book_id FROM book_search s " +
                        "JOIN book_search_map m ON s.rowid = m.rowid " +
                        "WHERE book_search MATCH ? AND m.book_id IN (${accessFilter.sql}) ORDER BY rank LIMIT ?"
                // Placeholder count: MATCH ? (1) + access-subquery args + LIMIT ? (1).
                val parameterCount = 1 + accessFilter.args.size + 1
                driver
                    .executeQuery(
                        identifier = null,
                        sql = sql,
                        mapper = { cursor ->
                            val out = mutableListOf<BookId>()
                            while (cursor.next().value) {
                                out += BookId(cursor.getString(0)!!)
                            }
                            QueryResult.Value(out.toList())
                        },
                        parameters = parameterCount,
                        binders = {
                            // Statement order — load-bearing for visibility.
                            var index = 0
                            bindString(index++, query)
                            accessFilter.args.forEach { arg -> bindRaw(index++, arg) }
                            bindLong(index, limit.toLong())
                        },
                    ).value
            }
        }

    /** Resolves the natural key `(library_id, root_rel_path)` to a [BookId], or null. */
    suspend fun findByPath(
        libraryId: LibraryId,
        rootRelPath: String,
    ): BookId? =
        suspendTransaction(db) {
            db.booksQueries
                .selectIdByNaturalKey(libraryId.value, rootRelPath)
                .executeAsOneOrNull()
                ?.let { BookId(it) }
        }

    /**
     * Resolves the move-detection key `(library_id, inode)` to a [BookId], or null.
     *
     * When two books share an inode (hardlinks), the first match by insertion
     * order is returned deterministically and a warning is logged — spec §5.3.
     */
    suspend fun findByInode(
        libraryId: LibraryId,
        inode: Long,
    ): BookId? =
        suspendTransaction(db) {
            val matches =
                db.booksQueries
                    .selectIdsByInode(libraryId.value, inode)
                    .executeAsList()
            if (matches.size > 1) {
                log.warn { "Multiple books share inode $inode in library ${libraryId.value}; picking first" }
            }
            matches.firstOrNull()?.let { BookId(it) }
        }

    /**
     * Bulk natural-key resolve: returns a `root_rel_path → BookId` map for every existing book in
     * [libraryId] whose `root_rel_path` is in [paths]. The batched counterpart to [findByPath] —
     * one [suspendTransaction] with an `IN (…)` query instead of a read txn per book. [paths] is
     * chunked under SQLite's bound-parameter ceiling so a large scan never overflows it.
     */
    suspend fun findExistingByPaths(
        libraryId: LibraryId,
        paths: Collection<String>,
    ): Map<String, BookId> {
        if (paths.isEmpty()) return emptyMap()
        return suspendTransaction(db) {
            paths
                .distinct()
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk ->
                    db.booksQueries.selectIdsByPaths(libraryId.value, chunk).executeAsList()
                }.associate { it.root_rel_path to BookId(it.id) }
        }
    }

    /**
     * Bulk move-detection resolve: returns an `inode → BookId` map for every existing book in
     * [libraryId] whose `inode` is in [inodes]. The batched counterpart to [findByInode] — one
     * [suspendTransaction] with an `IN (…)` query. When hardlinks share an inode the first row wins
     * deterministically (matching [findByInode]'s single-row pick); a warning per collision would
     * flood the log on a large scan, so the bulk path stays silent and relies on the rarity of the case.
     */
    suspend fun findExistingByInodes(
        libraryId: LibraryId,
        inodes: Collection<Long>,
    ): Map<Long, BookId> {
        if (inodes.isEmpty()) return emptyMap()
        return suspendTransaction(db) {
            inodes
                .distinct()
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk ->
                    db.booksQueries.selectIdsByInodes(libraryId.value, chunk).executeAsList()
                }.associate { it.inode!! to BookId(it.id) }
        }
    }

    /**
     * Returns the full book aggregates for every book that has a junction row for
     * [contributorId] in `book_contributors`.
     *
     * Used by [com.calypsan.listenup.server.api.ContributorServiceImpl.listBooksByContributor].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findByContributor(contributorId: ContributorId): List<BookSyncPayload> =
        suspendTransaction(db) {
            val bookIds =
                db.bookContributorsQueries
                    .bookIdsForContributor(contributorId.value)
                    .executeAsList()
            db.readBookPayloads(bookIds)
        }

    /**
     * Returns the full book aggregates for every book that has a membership row for
     * [seriesId] in `book_series_memberships`, in series-position order.
     *
     * Used by [com.calypsan.listenup.server.api.SeriesServiceImpl.listBooksBySeries].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findBySeries(seriesId: SeriesId): List<BookSyncPayload> =
        suspendTransaction(db) {
            val bookIds =
                db.bookSeriesMembershipsQueries
                    .bookIdsForSeries(seriesId.value)
                    .executeAsList()
            db.readBookPayloads(bookIds)
        }
}
