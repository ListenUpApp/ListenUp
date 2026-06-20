package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.SqlFragment
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction as exposedSuspendTransaction

private val log = KotlinLogging.logger {}

/**
 * Transaction-scoped read helpers for the books aggregate, over the generated SQLDelight
 * queries.
 *
 * Each method opens its own short read transaction — they do NOT bump the global revision,
 * the change bus, or any write query. The caller ([BookRepository]) provides [db] (the
 * SQLDelight handle) and [exposedDb] (used only for the access-filtered FTS read, whose
 * spliced subquery arrives as a parameterised [SqlFragment] of Exposed-typed args — a
 * read, so it runs safely on the Exposed connection over the same WAL file).
 *
 * @param db the SQLDelight database for the generated reads + aggregate hydration.
 * @param exposedDb the Exposed database used only by the dynamic access-filtered [searchFts]
 *   path, where the runtime-built subquery cannot be a static SQLDelight query.
 */
internal class BookFinder(
    private val db: ListenUpDatabase,
    private val exposedDb: Database,
) {
    /**
     * Runs an FTS5 full-text search against `book_search` and returns matching
     * [BookId]s in rank order (best match first), capped at [limit] results.
     *
     * The unfiltered path uses the generated `searchBookIds` query (the static
     * MATCH+join+rank shape). When [accessFilter] is non-null its `SELECT b2.id …`
     * subquery must be spliced as `AND m.book_id IN (<sql>)` — a runtime-built fragment
     * that no static query can express — so that path runs the same raw read the Exposed
     * code did, on [exposedDb] (a read over the shared WAL file). Args are spliced in
     * statement order: `MATCH ?` → the access subquery's args → `LIMIT ?`.
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
            exposedSuspendTransaction(exposedDb) {
                val results = mutableListOf<BookId>()
                val stmt =
                    "SELECT m.book_id FROM book_search s " +
                        "JOIN book_search_map m ON s.rowid = m.rowid " +
                        "WHERE book_search MATCH ? AND m.book_id IN (${accessFilter.sql}) ORDER BY rank LIMIT ?"
                val args =
                    listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to query) +
                        accessFilter.args +
                        listOf(IntegerColumnType() to limit)
                TransactionManager.current().exec(stmt = stmt, args = args) { rs ->
                    while (rs.next()) results += BookId(rs.getString(1))
                }
                results
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
