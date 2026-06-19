package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.sync.SqlFragment
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val log = KotlinLogging.logger {}

/**
 * Transaction-scoped read helpers for the books aggregate.
 *
 * Each method opens its own short read transaction via [suspendTransaction] —
 * they do NOT call [com.calypsan.listenup.server.sync.nextRevision], the change
 * bus, or any write DSL. The caller ([BookRepository]) provides [db].
 */
internal class BookFinder(
    private val db: Database,
) {
    /**
     * Runs an FTS5 full-text search against `book_search` and returns matching
     * [BookId]s in rank order (best match first), capped at [limit] results.
     *
     * Joins `book_search_map` to translate FTS5 integer rowids back to the
     * string book ids this application uses. The search query is parameterised
     * so user-supplied strings never touch the SQL text. [limit] is clamped
     * by the caller ([BookServiceImpl]) before this method is invoked.
     *
     * When [accessFilter] is non-null its `SELECT b2.id …` subquery is spliced as
     * `AND m.book_id IN (<sql>)` so only ids the viewer can reach survive — the
     * caller ([BookServiceImpl.searchBooks]) derives it from
     * [BookAccessPolicy.accessibleBookIdsSql][com.calypsan.listenup.server.api.BookAccessPolicy.accessibleBookIdsSql],
     * which yields `null` for ROOT/ADMIN (unfiltered). Args are spliced in statement
     * order: `MATCH ?` → the access subquery's args → `LIMIT ?`.
     */
    suspend fun searchFts(
        query: String,
        limit: Int,
        accessFilter: SqlFragment? = null,
    ): List<BookId> =
        suspendTransaction(db) {
            val results = mutableListOf<BookId>()
            val accessClause = if (accessFilter != null) " AND m.book_id IN (${accessFilter.sql})" else ""
            val stmt =
                "SELECT m.book_id FROM book_search s " +
                    "JOIN book_search_map m ON s.rowid = m.rowid " +
                    "WHERE book_search MATCH ?" + accessClause + " ORDER BY rank LIMIT ?"
            val args =
                listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to query) +
                    (accessFilter?.args ?: emptyList()) +
                    listOf(IntegerColumnType() to limit)
            TransactionManager.current().exec(stmt = stmt, args = args) { rs ->
                while (rs.next()) results += BookId(rs.getString(1))
            }
            results
        }

    /** Resolves the natural key `(library_id, root_rel_path)` to a [BookId], or null. */
    suspend fun findByPath(
        libraryId: LibraryId,
        rootRelPath: String,
    ): BookId? =
        suspendTransaction(db) {
            BookTable
                .selectAll()
                .where {
                    (BookTable.libraryId eq libraryId.value) and (BookTable.rootRelPath eq rootRelPath)
                }.firstOrNull()
                ?.get(BookTable.id)
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
                BookTable
                    .selectAll()
                    .where { (BookTable.libraryId eq libraryId.value) and (BookTable.inode eq inode) }
                    .map { it[BookTable.id] }
            if (matches.size > 1) {
                log.warn { "Multiple books share inode $inode in library ${libraryId.value}; picking first" }
            }
            matches.firstOrNull()?.let { BookId(it) }
        }

    /**
     * Returns the full book aggregates for every book that has a junction row for
     * [contributorId] in [BookContributorTable]. Results are ordered by book
     * [BookTable.createdAt] ascending (stable, scan-insertion order).
     *
     * Used by [com.calypsan.listenup.server.api.ContributorServiceImpl.listBooksByContributor].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findByContributor(contributorId: ContributorId): List<BookSyncPayload> =
        suspendTransaction(db) {
            val bookIds =
                BookContributorTable
                    .select(BookContributorTable.bookId)
                    .where { BookContributorTable.contributorId eq contributorId.value }
                    .map { it[BookContributorTable.bookId] }
            readBookPayloads(bookIds)
        }

    /**
     * Returns the full book aggregates for every book that has a membership row for
     * [seriesId] in [BookSeriesMembershipTable]. Results are ordered by
     * [BookSeriesMembershipTable.ordinal] ascending (series-position order).
     *
     * Used by [com.calypsan.listenup.server.api.SeriesServiceImpl.listBooksBySeries].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findBySeries(seriesId: SeriesId): List<BookSyncPayload> =
        suspendTransaction(db) {
            val bookIds =
                BookSeriesMembershipTable
                    .select(BookSeriesMembershipTable.bookId)
                    .where { BookSeriesMembershipTable.seriesId eq seriesId.value }
                    .orderBy(BookSeriesMembershipTable.ordinal)
                    .map { it[BookSeriesMembershipTable.bookId] }
            readBookPayloads(bookIds)
        }
}
