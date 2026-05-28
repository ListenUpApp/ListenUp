package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.dto.BookHit
import com.calypsan.listenup.api.dto.ContributorHit
import com.calypsan.listenup.api.dto.SearchFacets
import com.calypsan.listenup.api.dto.SearchFilters
import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.dto.SearchSort
import com.calypsan.listenup.api.dto.SeriesHit
import com.calypsan.listenup.api.dto.TagHit
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.TagId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Server-side implementation of [SearchService].
 *
 * Runs up to four parallel FTS5 queries (books, contributors, series, tags) as concurrent
 * coroutines, each inside its own [suspendTransaction]. The results are combined into a single
 * [SearchResults] envelope. When [SearchQuery.filters] is active or [SearchQuery.sort] is
 * non-[SearchSort.Relevance], the search collapses to a single books-only query so that
 * filter and sort semantics apply without polluting unrelated result categories.
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
    private val facetCounter: SearchFacetCounter = SearchFacetCounter(),
) : SearchService {
    override suspend fun search(query: SearchQuery): AppResult<SearchResults> {
        if (query.text.isBlank()) {
            return AppResult.Success(EMPTY_RESULTS)
        }
        val safeLimit = query.limit.coerceIn(1, MAX_LIMIT)
        val ftsQuery = sanitizeFts5Query(query.text)
        if (ftsQuery.isBlank()) {
            return AppResult.Success(EMPTY_RESULTS)
        }
        val booksOnly = query.filters?.isActive == true || query.sort != SearchSort.Relevance
        val (mbSql, mbArgs) = matchedBooksSqlAndArgs(ftsQuery, query.filters)
        return coroutineScope {
            // Facet DB work depends only on the matched-book subquery, so it runs concurrently
            // with the book/contributor/series/tag search queries — preserving the search-latency
            // budget instead of running sequentially after the searches await.
            val facetsDeferred = async { computeFacets(mbSql, mbArgs) }
            val booksDeferred = async { searchBooks(ftsQuery, safeLimit, query.filters, query.sort) }
            if (booksOnly) {
                val books = booksDeferred.await()
                AppResult.Success(
                    SearchResults(
                        books = books,
                        contributors = emptyList(),
                        series = emptyList(),
                        tags = emptyList(),
                        // Books-only collapse: contributors/series/tags are all empty, so the
                        // facet's placeholder zero type counts are already correct.
                        facets = facetsDeferred.await(),
                    ),
                )
            } else {
                val contributorsDeferred = async { searchContributors(ftsQuery, safeLimit) }
                val seriesDeferred = async { searchSeries(ftsQuery, safeLimit) }
                val tagsDeferred = async { searchTags(ftsQuery, safeLimit) }
                val books = booksDeferred.await()
                val contributors = contributorsDeferred.await()
                val series = seriesDeferred.await()
                val tags = tagsDeferred.await()
                AppResult.Success(
                    SearchResults(
                        books = books,
                        contributors = contributors,
                        series = series,
                        tags = tags,
                        // `types.books` is the true matched count from the facet query; the other
                        // type counts are the (limited) display-list sizes, filled in here.
                        facets =
                            facetsDeferred.await().let {
                                it.copy(
                                    types =
                                        it.types.copy(
                                            contributors = contributors.size,
                                            series = series.size,
                                            tags = tags.size,
                                        ),
                                )
                            },
                    ),
                )
            }
        }
    }

    /**
     * Builds the matched-books subquery — the SAME FROM/WHERE as [searchBooks] minus ORDER BY
     * and LIMIT — plus its positional args (fts query first, then the filter args). The facet
     * counter joins genres/contributors against this subquery so counts agree with the filtered
     * (but un-limited) book set.
     */
    private fun matchedBooksSqlAndArgs(
        ftsQuery: String,
        filters: SearchFilters?,
    ): Pair<String, List<Pair<IColumnType<*>, Any>>> {
        val filterSql = buildFilterSql(filters)
        val sql =
            "SELECT b.id FROM book_search s JOIN book_search_map m ON s.rowid = m.rowid " +
                "JOIN books b ON b.id = m.book_id WHERE book_search MATCH ? AND b.deleted_at IS NULL" +
                filterSql.whereFragment
        val args = listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to ftsQuery) + filterSql.args
        return sql to args
    }

    /**
     * Computes facets in a fresh transaction. The genre/author/narrator buckets and the
     * `types.books` count are over the FULL matched set (no display limit), so `types.books`
     * is computed with a `COUNT(*)` over the matched subquery rather than the limited book list.
     *
     * Depends only on the matched-book subquery — not on the other result lists — so the caller
     * runs this concurrently with the search queries. The returned [SearchFacets.types] carries
     * the true matched `books` count with `contributors`/`series`/`tags` left at `0`; the caller
     * fills those in from its display-list sizes once the parallel searches resolve.
     */
    private suspend fun computeFacets(
        mbSql: String,
        mbArgs: List<Pair<IColumnType<*>, Any>>,
    ): SearchFacets =
        suspendTransaction(db) {
            var matchedBooks = 0
            TransactionManager.current().exec(
                stmt = "SELECT COUNT(*) AS cnt FROM ($mbSql)",
                args = mbArgs,
            ) { rs -> if (rs.next()) matchedBooks = rs.getInt("cnt") }
            facetCounter.count(
                matchedBooksSql = mbSql,
                args = mbArgs,
                bookCount = matchedBooks,
            )
        }

    private suspend fun searchBooks(
        ftsQuery: String,
        limit: Int,
        filters: SearchFilters?,
        sort: SearchSort,
    ): List<BookHit> =
        suspendTransaction(db) {
            val filterSql = buildFilterSql(filters)
            val results = mutableListOf<BookHit>()
            // Join book_search → book_search_map → books and fetch author names via a
            // GROUP_CONCAT sub-select so there is no N+1 per-book round-trip.
            // Filter args are spliced BETWEEN the ftsQuery arg and the limit arg so
            // positional binding matches the statement order: MATCH ? … <filters> … LIMIT ?
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
                        "AND b.deleted_at IS NULL" +
                        filterSql.whereFragment +
                        " ORDER BY ${orderByFor(sort)} LIMIT ?",
                args =
                    listOf(TextColumnType() to ftsQuery) +
                        filterSql.args +
                        listOf(IntegerColumnType() to limit),
            ) { rs ->
                while (rs.next()) {
                    val authorsCsv = rs.getString("author_names")
                    val authorNames = if (authorsCsv.isNullOrBlank()) emptyList() else authorsCsv.split(", ")
                    val title = rs.getString("title")
                    results +=
                        BookHit(
                            id = BookId(rs.getString("id")),
                            title = title,
                            authorNames = authorNames,
                            coverPath = rs.getString("cover_path"),
                            coverHash = rs.getString("cover_hash"),
                            highlight =
                                highlightMatches(title, ftsQuery)
                                    ?: authorNames.firstNotNullOfOrNull { highlightMatches(it, ftsQuery) },
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
                    val name = rs.getString("name")
                    results +=
                        ContributorHit(
                            id = ContributorId(rs.getString("id")),
                            name = name,
                            sortName = rs.getString("sort_name"),
                            photoPath = rs.getString("image_path"),
                            photoBlurHash = rs.getString("image_blur_hash"),
                            bookCount = rs.getInt(COL_BOOK_COUNT),
                            highlight = highlightMatches(name, ftsQuery),
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
                    val name = rs.getString("name")
                    results +=
                        SeriesHit(
                            id = SeriesId(rs.getString("id")),
                            name = name,
                            sortName = rs.getString("sort_name"),
                            coverPath = rs.getString("cover_path"),
                            coverBlurHash = rs.getString("cover_blur_hash"),
                            bookCount = rs.getInt(COL_BOOK_COUNT),
                            highlight = highlightMatches(name, ftsQuery),
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
                    val name = rs.getString("name")
                    results +=
                        TagHit(
                            id = TagId(rs.getString("id")),
                            name = name,
                            slug = rs.getString("slug"),
                            bookCount = rs.getLong(COL_BOOK_COUNT),
                            highlight = highlightMatches(name, ftsQuery),
                        )
                }
            }
            results
        }

    /**
     * Maps a [SearchSort] to an SQL `ORDER BY` fragment.
     *
     * [SearchSort] is a closed enum — its variants are never user text — so
     * interpolating the mapped fragment directly into the SQL template is safe.
     * `COLLATE NOCASE` on sort_title ensures case-insensitive alphabetical order
     * across mixed-case titles (e.g. "the hobbit" sorts with "The Hobbit").
     */
    private fun orderByFor(sort: SearchSort): String =
        when (sort) {
            SearchSort.Relevance -> "s.rank"
            SearchSort.Title -> "b.sort_title COLLATE NOCASE"
            SearchSort.Recent -> "b.updated_at DESC"
            SearchSort.Duration -> "b.total_duration ASC"
        }

    private data class FilterSql(
        val whereFragment: String,
        val args: List<Pair<IColumnType<*>, Any>>,
    )

    private fun buildFilterSql(filters: SearchFilters?): FilterSql {
        if (filters == null || !filters.isActive) return FilterSql("", emptyList())
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Pair<IColumnType<*>, Any>>()
        val genreClauses = mutableListOf<String>()
        if (filters.genreSlugs.isNotEmpty()) {
            val placeholders = filters.genreSlugs.joinToString(",") { "?" }
            genreClauses += "g.slug IN ($placeholders)"
            filters.genreSlugs.forEach { args += TextColumnType() to it }
        }
        if (filters.genrePath != null) {
            // Kotlin doesn't compose the outer `filters != null` smart-cast with this nested
            // property null-check, so !! is required to satisfy the Pair<…, Any> bound.
            val path = filters.genrePath!!
            genreClauses += "(g.path = ? OR g.path LIKE ? || '/%')"
            args.add(TextColumnType() to path)
            args.add(TextColumnType() to path)
        }
        if (genreClauses.isNotEmpty()) {
            clauses +=
                "EXISTS (SELECT 1 FROM book_genres bg JOIN genres g ON g.id = bg.genre_id " +
                "WHERE bg.book_id = b.id AND g.deleted_at IS NULL AND (${genreClauses.joinToString(" OR ")}))"
        }
        // total_duration is stored in milliseconds; contract fields are in seconds → multiply by 1000.
        // !! required: Kotlin doesn't compose the outer `filters != null` smart-cast with nested
        // property null-checks, so we must assert non-null to satisfy Pair<IColumnType<*>, Any>.
        if (filters.durationMinSeconds != null) {
            clauses += "b.total_duration >= ?"
            args += LongColumnType() to filters.durationMinSeconds!! * 1_000L
        }
        if (filters.durationMaxSeconds != null) {
            clauses += "b.total_duration <= ?"
            args += LongColumnType() to filters.durationMaxSeconds!! * 1_000L
        }
        // publish_year is nullable — NULL rows are naturally excluded by >= / <= comparisons.
        if (filters.yearMin != null) {
            clauses += "b.publish_year >= ?"
            args += IntegerColumnType() to filters.yearMin!!
        }
        if (filters.yearMax != null) {
            clauses += "b.publish_year <= ?"
            args += IntegerColumnType() to filters.yearMax!!
        }
        return FilterSql(
            whereFragment = if (clauses.isEmpty()) "" else " AND " + clauses.joinToString(" AND "),
            args = args,
        )
    }

    private companion object {
        const val MAX_LIMIT = 100
        const val COL_BOOK_COUNT = "book_count"
        val EMPTY_RESULTS =
            SearchResults(
                books = emptyList(),
                contributors = emptyList(),
                series = emptyList(),
                tags = emptyList(),
            )

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
