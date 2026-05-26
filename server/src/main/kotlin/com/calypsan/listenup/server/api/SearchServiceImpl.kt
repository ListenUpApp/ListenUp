package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.dto.BookHit
import com.calypsan.listenup.api.dto.ContributorHit
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.dto.SeriesHit
import com.calypsan.listenup.api.dto.TagHit
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.TagId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Server-side implementation of [SearchService].
 *
 * Runs three FTS5 queries (books, contributors, series) as concurrent coroutines,
 * each inside its own [suspendTransaction]. The results are combined into a single
 * [SearchResults] envelope.
 *
 * User-supplied queries are sanitised by [sanitizeFts5Query] before they reach
 * the FTS5 MATCH predicate — any character outside `[A-Za-z0-9 ]` is replaced
 * with a space so no FTS5 operator syntax can be injected. The sanitised string
 * is always bound as a parameterised `?` argument; it never touches the SQL
 * template string itself.
 *
 * Book author names are fetched in the same query via a `GROUP_CONCAT` sub-select
 * — no N+1 per-book round-trip.
 */
internal class SearchServiceImpl(
    private val db: Database,
) : SearchService {
    override suspend fun search(
        query: String,
        limit: Int,
    ): AppResult<SearchResults> {
        if (query.isBlank()) {
            return AppResult.Success(SearchResults(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        val safeLimit = limit.coerceIn(1, MAX_LIMIT)
        val ftsQuery = sanitizeFts5Query(query)
        if (ftsQuery.isBlank()) {
            return AppResult.Success(SearchResults(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        return coroutineScope {
            val booksDeferred = async { searchBooks(ftsQuery, safeLimit) }
            val contributorsDeferred = async { searchContributors(ftsQuery, safeLimit) }
            val seriesDeferred = async { searchSeries(ftsQuery, safeLimit) }
            val tagsDeferred = async { searchTags(ftsQuery, safeLimit) }
            AppResult.Success(
                SearchResults(
                    books = booksDeferred.await(),
                    contributors = contributorsDeferred.await(),
                    series = seriesDeferred.await(),
                    tags = tagsDeferred.await(),
                ),
            )
        }
    }

    private suspend fun searchBooks(
        ftsQuery: String,
        limit: Int,
    ): List<BookHit> =
        suspendTransaction(db) {
            val results = mutableListOf<BookHit>()
            // Join book_search → book_search_map → books and fetch author names via a
            // GROUP_CONCAT sub-select so there is no N+1 per-book round-trip.
            TransactionManager.current().exec(
                stmt =
                    "SELECT b.id, b.title, b.cover_path, b.cover_hash, " +
                        "(SELECT GROUP_CONCAT(c.name, ', ') " +
                        "   FROM book_contributors bc " +
                        "   JOIN contributors c ON c.id = bc.contributor_id " +
                        "  WHERE bc.book_id = b.id AND bc.role = 'author' " +
                        "  ORDER BY bc.ordinal) AS author_names " +
                        "FROM book_search s " +
                        "JOIN book_search_map m ON s.rowid = m.rowid " +
                        "JOIN books b ON b.id = m.book_id " +
                        "WHERE book_search MATCH ? " +
                        "AND b.deleted_at IS NULL " +
                        "ORDER BY s.rank LIMIT ?",
                args =
                    listOf(
                        TextColumnType() to ftsQuery,
                        IntegerColumnType() to limit,
                    ),
            ) { rs ->
                while (rs.next()) {
                    val authorsCsv = rs.getString("author_names")
                    val authorNames = if (authorsCsv.isNullOrBlank()) emptyList() else authorsCsv.split(", ")
                    results +=
                        BookHit(
                            id = BookId(rs.getString("id")),
                            title = rs.getString("title"),
                            authorNames = authorNames,
                            coverPath = rs.getString("cover_path"),
                            coverHash = rs.getString("cover_hash"),
                        )
                }
            }
            results
        }

    private suspend fun searchContributors(
        ftsQuery: String,
        limit: Int,
    ): List<ContributorHit> =
        suspendTransaction(db) {
            val results = mutableListOf<ContributorHit>()
            TransactionManager.current().exec(
                stmt =
                    "SELECT c.id, c.name, c.sort_name, c.image_path, c.image_blur_hash, " +
                        "(SELECT COUNT(*) FROM book_contributors bc WHERE bc.contributor_id = c.id) AS book_count " +
                        "FROM contributor_search cs " +
                        "JOIN contributors c ON c.rowid = cs.rowid " +
                        "WHERE contributor_search MATCH ? " +
                        "AND c.deleted_at IS NULL " +
                        "ORDER BY cs.rank LIMIT ?",
                args =
                    listOf(
                        TextColumnType() to ftsQuery,
                        IntegerColumnType() to limit,
                    ),
            ) { rs ->
                while (rs.next()) {
                    results +=
                        ContributorHit(
                            id = ContributorId(rs.getString("id")),
                            name = rs.getString("name"),
                            sortName = rs.getString("sort_name"),
                            photoPath = rs.getString("image_path"),
                            photoBlurHash = rs.getString("image_blur_hash"),
                            bookCount = rs.getInt(COL_BOOK_COUNT),
                        )
                }
            }
            results
        }

    private suspend fun searchSeries(
        ftsQuery: String,
        limit: Int,
    ): List<SeriesHit> =
        suspendTransaction(db) {
            val results = mutableListOf<SeriesHit>()
            TransactionManager.current().exec(
                stmt =
                    "SELECT s.id, s.name, s.sort_name, s.cover_path, s.cover_blur_hash, " +
                        "(SELECT COUNT(*) FROM book_series_memberships bsm WHERE bsm.series_id = s.id) AS book_count " +
                        "FROM series_search ss " +
                        "JOIN book_series s ON s.rowid = ss.rowid " +
                        "WHERE series_search MATCH ? " +
                        "AND s.deleted_at IS NULL " +
                        "ORDER BY ss.rank LIMIT ?",
                args =
                    listOf(
                        TextColumnType() to ftsQuery,
                        IntegerColumnType() to limit,
                    ),
            ) { rs ->
                while (rs.next()) {
                    results +=
                        SeriesHit(
                            id = SeriesId(rs.getString("id")),
                            name = rs.getString("name"),
                            sortName = rs.getString("sort_name"),
                            coverPath = rs.getString("cover_path"),
                            coverBlurHash = rs.getString("cover_blur_hash"),
                            bookCount = rs.getInt(COL_BOOK_COUNT),
                        )
                }
            }
            results
        }

    private suspend fun searchTags(
        ftsQuery: String,
        limit: Int,
    ): List<TagHit> =
        suspendTransaction(db) {
            val results = mutableListOf<TagHit>()
            // JOIN tag_search → tags; COUNT(*) sub-select computes live bookCount
            // without a denormalized column — correctness over cleverness at our scale.
            // Soft-deleted tags (t.deleted_at IS NULL) and soft-deleted junction rows
            // (bt.deleted_at IS NULL) are both excluded so the count reflects live state.
            TransactionManager.current().exec(
                stmt =
                    "SELECT t.id, t.name, t.slug, " +
                        "(SELECT COUNT(*) FROM book_tags bt " +
                        " WHERE bt.tag_id = t.id AND bt.deleted_at IS NULL) AS book_count " +
                        "FROM tag_search ts " +
                        "JOIN tags t ON t.rowid = ts.rowid " +
                        "WHERE tag_search MATCH ? " +
                        "AND t.deleted_at IS NULL " +
                        "ORDER BY ts.rank LIMIT ?",
                args =
                    listOf(
                        TextColumnType() to ftsQuery,
                        IntegerColumnType() to limit,
                    ),
            ) { rs ->
                while (rs.next()) {
                    results +=
                        TagHit(
                            id = TagId(rs.getString("id")),
                            name = rs.getString("name"),
                            slug = rs.getString("slug"),
                            bookCount = rs.getLong(COL_BOOK_COUNT),
                        )
                }
            }
            results
        }

    private companion object {
        const val MAX_LIMIT = 100
        const val COL_BOOK_COUNT = "book_count"

        /**
         * Strips any character that FTS5 treats as a query operator.
         *
         * FTS5 MATCH syntax uses `"`, `-`, `*`, `+`, `(`, `)`, `:`, `^`, and
         * control characters as operators. Replacing everything outside
         * `[A-Za-z0-9 ]` with a space is the simplest safe sanitisation: it
         * preserves the search intent for all practical user input while
         * guaranteeing no operator injection is possible. The resulting string
         * is still passed as a parameterised `?` argument — this function
         * defends against FTS5 syntax injection, not SQL injection.
         */
        fun sanitizeFts5Query(q: String): String = q.replace(Regex("[^A-Za-z0-9 ]"), " ").trim()
    }
}
