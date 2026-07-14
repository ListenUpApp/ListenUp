package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
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
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.bindRaw
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
 *
 * **Engine-neutral over the SQLDelight driver.** The user-facing book search splices runtime SQL
 * fragments — the access-control `AND b.id IN (<subquery>)` from [BookAccessPolicy], optional
 * genre/duration/year filters from [buildFilterSql], and a sort override — each carrying plain
 * raw positional args ([SqlFragment.args]). Every dynamic read runs over the shared [driver]
 * inside a [suspendTransaction], the same engine that backs every other read on the migrated db
 * file; there is no Exposed dependency here. The `book_search` *writes* (upsert, reindex) are
 * SQLDelight too.
 *
 * Results AND facet counts are access-gated through [BookAccessPolicy]: a member must
 * never see a book they can't reach in the result list OR in a facet bucket count (a
 * count leaks existence just as surely as a row). The authenticated caller is resolved
 * from [principal] (never from request fields); [BookAccessPolicy.accessibleBookIdsSql]
 * yields a `b.id IN (…)` fragment that is spliced into the one matched-books subquery
 * the result SELECT and every facet/`bookCount` query derive from — so gating it once
 * covers every surface. ROOT/ADMIN (and the unscoped placeholder used by direct
 * construction in tests) get a `null` fragment, i.e. no filter. Route handlers call
 * [copyWith] to bind each request to the authenticated principal.
 */
internal class SearchServiceImpl(
    private val db: ListenUpDatabase,
    private val driver: SqlDriver,
    private val facetCounter: SearchFacetCounter = SearchFacetCounter(),
    private val accessPolicy: BookAccessPolicy = BookAccessPolicy(db, driver),
    private val principal: PrincipalProvider = PrincipalProvider.None,
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
        // The viewer's access filter — null for ROOT/ADMIN or an unscoped caller (no filter).
        // Resolved once and spliced into both the result SELECT and the matched-books subquery
        // the facets/bookCount build from, so every surface is gated identically.
        val access =
            principal.current()?.let { accessPolicy.accessibleBookIdsSql(it.userId.value, it.role) }
        val booksOnly = query.filters?.isActive == true || query.sort != SearchSort.Relevance
        val (mbSql, mbArgs) = matchedBooksSqlAndArgs(ftsQuery, query.filters, access)
        return coroutineScope {
            // Facet DB work depends only on the matched-book subquery, so it runs concurrently
            // with the book/contributor/series/tag search queries — preserving the search-latency
            // budget instead of running sequentially after the searches await.
            val facetsDeferred = async { computeFacets(mbSql, mbArgs) }
            val booksDeferred = async { searchBooks(ftsQuery, safeLimit, query.filters, query.sort, access) }
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

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): SearchServiceImpl =
        SearchServiceImpl(
            db = db,
            driver = driver,
            facetCounter = facetCounter,
            accessPolicy = accessPolicy,
            principal = principal,
        )

    /**
     * Builds the matched-books subquery — the SAME FROM/WHERE as [searchBooks] minus ORDER BY
     * and LIMIT — plus its positional args (fts query first, then the filter args, then the
     * viewer's access-subquery args). The facet counter joins genres/contributors against this
     * subquery so counts agree with the filtered (but un-limited) book set, and the access clause
     * is spliced in here so result list and facet counts share one visibility boundary.
     *
     * @param access the viewer's accessible-book-id subquery, or `null` for ROOT/ADMIN/unscoped
     *   (no access filter). When present its `?` args append AFTER the filter args, matching the
     *   `… <filter> AND b.id IN (<access sql>)` statement order.
     */
    private fun matchedBooksSqlAndArgs(
        ftsQuery: String,
        filters: SearchFilters?,
        access: SqlFragment?,
    ): Pair<String, List<Any?>> {
        val filterSql = buildFilterSql(filters)
        val accessClause = if (access != null) " AND b.id IN (${access.sql})" else ""
        val sql =
            "SELECT b.id FROM book_search s JOIN book_search_map m ON s.rowid = m.rowid " +
                "JOIN books b ON b.id = m.book_id WHERE book_search MATCH ? AND b.deleted_at IS NULL" +
                filterSql.whereFragment +
                accessClause
        val args =
            listOf<Any?>(ftsQuery) +
                filterSql.args +
                (access?.args ?: emptyList())
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
        mbArgs: List<Any?>,
    ): SearchFacets =
        suspendTransaction(db) {
            val matchedBooks =
                driver
                    .querySingleInt("SELECT COUNT(*) AS cnt FROM ($mbSql)", mbArgs)
                    ?: 0
            facetCounter.count(
                driver = driver,
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
        access: SqlFragment?,
    ): List<BookHit> =
        suspendTransaction(db) {
            val filterSql = buildFilterSql(filters)
            val accessClause = if (access != null) " AND b.id IN (${access.sql})" else ""
            // Join book_search → book_search_map → books and fetch author names via a
            // GROUP_CONCAT sub-select so there is no N+1 per-book round-trip.
            // Args are bound in statement order so positional binding matches:
            // MATCH ? … <filters> … <access subquery> … LIMIT ?
            val sql =
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
                    accessClause +
                    " ORDER BY ${orderByFor(sort)} LIMIT ?"
            val args = listOf<Any?>(ftsQuery) + filterSql.args + (access?.args ?: emptyList()) + listOf(limit.toLong())
            driver.queryList(sql, args) { cursor ->
                val authorsCsv = cursor.getString(4)
                val authorNames = if (authorsCsv.isNullOrBlank()) emptyList() else authorsCsv.split(", ")
                val title = cursor.getString(1)!!
                BookHit(
                    id = BookId(cursor.getString(0)!!),
                    title = title,
                    authorNames = authorNames,
                    coverPath = cursor.getString(2),
                    coverHash = cursor.getString(3),
                    highlight =
                        highlightMatches(title, ftsQuery)
                            ?: authorNames.firstNotNullOfOrNull { highlightMatches(it, ftsQuery) },
                )
            }
        }

    private suspend fun searchContributors(
        ftsQuery: String,
        limit: Int,
    ): List<ContributorHit> =
        suspendTransaction(db) {
            val sql =
                "SELECT c.id, c.name, c.sort_name, c.image_path, c.image_blur_hash, " +
                    "(SELECT COUNT(*) FROM book_contributors bc WHERE bc.contributor_id = c.id) AS book_count " +
                    "FROM contributor_search cs " +
                    "JOIN contributors c ON c.rowid = cs.rowid " +
                    "WHERE contributor_search MATCH ? " +
                    "AND c.deleted_at IS NULL " +
                    "ORDER BY cs.rank LIMIT ?"
            driver.queryList(sql, listOf(ftsQuery, limit.toLong())) { cursor ->
                val name = cursor.getString(1)!!
                ContributorHit(
                    id = ContributorId(cursor.getString(0)!!),
                    name = name,
                    sortName = cursor.getString(2),
                    photoPath = cursor.getString(3),
                    photoBlurHash = cursor.getString(4),
                    bookCount = cursor.getLong(5)!!.toInt(),
                    highlight = highlightMatches(name, ftsQuery),
                )
            }
        }

    private suspend fun searchSeries(
        ftsQuery: String,
        limit: Int,
    ): List<SeriesHit> =
        suspendTransaction(db) {
            val sql =
                "SELECT s.id, s.name, s.sort_name, s.cover_path, s.cover_blur_hash, " +
                    "(SELECT COUNT(*) FROM book_series_memberships bsm WHERE bsm.series_id = s.id) AS book_count " +
                    "FROM series_search ss " +
                    "JOIN book_series s ON s.rowid = ss.rowid " +
                    "WHERE series_search MATCH ? " +
                    "AND s.deleted_at IS NULL " +
                    "ORDER BY ss.rank LIMIT ?"
            driver.queryList(sql, listOf(ftsQuery, limit.toLong())) { cursor ->
                val name = cursor.getString(1)!!
                SeriesHit(
                    id = SeriesId(cursor.getString(0)!!),
                    name = name,
                    sortName = cursor.getString(2),
                    coverPath = cursor.getString(3),
                    coverBlurHash = cursor.getString(4),
                    bookCount = cursor.getLong(5)!!.toInt(),
                    highlight = highlightMatches(name, ftsQuery),
                )
            }
        }

    private suspend fun searchTags(
        ftsQuery: String,
        limit: Int,
    ): List<TagHit> =
        suspendTransaction(db) {
            // JOIN tag_search → tags; COUNT(*) sub-select computes live bookCount
            // without a denormalized column — correctness over cleverness at our scale.
            // Soft-deleted tags (t.deleted_at IS NULL) and soft-deleted junction rows
            // (bt.deleted_at IS NULL) are both excluded so the count reflects live state.
            val sql =
                "SELECT t.id, t.name, t.slug, " +
                    "(SELECT COUNT(*) FROM book_tags bt " +
                    " WHERE bt.tag_id = t.id AND bt.deleted_at IS NULL) AS book_count " +
                    "FROM tag_search ts " +
                    "JOIN tags t ON t.rowid = ts.rowid " +
                    "WHERE tag_search MATCH ? " +
                    "AND t.deleted_at IS NULL " +
                    "ORDER BY ts.rank LIMIT ?"
            driver.queryList(sql, listOf(ftsQuery, limit.toLong())) { cursor ->
                val name = cursor.getString(1)!!
                TagHit(
                    id = TagId(cursor.getString(0)!!),
                    name = name,
                    slug = cursor.getString(2)!!,
                    bookCount = cursor.getLong(3)!!,
                    highlight = highlightMatches(name, ftsQuery),
                )
            }
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
        val args: List<Any?>,
    )

    private fun buildFilterSql(filters: SearchFilters?): FilterSql {
        if (filters == null || !filters.isActive) return FilterSql("", emptyList())
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        val genreClauses = mutableListOf<String>()
        if (filters.genreSlugs.isNotEmpty()) {
            val placeholders = filters.genreSlugs.joinToString(",") { "?" }
            genreClauses += "g.slug IN ($placeholders)"
            filters.genreSlugs.forEach { args += it }
        }
        if (filters.genrePath != null) {
            // Kotlin doesn't compose the outer `filters != null` smart-cast with this nested
            // property null-check, so !! is required.
            val path = filters.genrePath!!
            genreClauses += "(g.path = ? OR g.path LIKE ? || '/%')"
            args.add(path)
            args.add(path)
        }
        if (genreClauses.isNotEmpty()) {
            clauses +=
                "EXISTS (SELECT 1 FROM book_genres bg JOIN genres g ON g.id = bg.genre_id " +
                "WHERE bg.book_id = b.id AND g.deleted_at IS NULL AND (${genreClauses.joinToString(" OR ")}))"
        }
        // total_duration is stored in milliseconds; contract fields are in seconds → multiply by 1000.
        // !! required: Kotlin doesn't compose the outer `filters != null` smart-cast with nested
        // property null-checks.
        if (filters.durationMinSeconds != null) {
            clauses += "b.total_duration >= ?"
            args += filters.durationMinSeconds!! * 1_000L
        }
        if (filters.durationMaxSeconds != null) {
            clauses += "b.total_duration <= ?"
            args += filters.durationMaxSeconds!! * 1_000L
        }
        // publish_year is nullable — NULL rows are naturally excluded by >= / <= comparisons.
        if (filters.yearMin != null) {
            clauses += "b.publish_year >= ?"
            args += filters.yearMin!!.toLong()
        }
        if (filters.yearMax != null) {
            clauses += "b.publish_year <= ?"
            args += filters.yearMax!!.toLong()
        }
        return FilterSql(
            whereFragment = if (clauses.isEmpty()) "" else " AND " + clauses.joinToString(" AND "),
            args = args,
        )
    }

    private companion object {
        const val MAX_LIMIT = 100
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

/**
 * Constructs a [SearchService] backed by [SearchServiceImpl]. Public so cross-module test
 * harnesses (e.g. `:sharedLogic:jvmTest`'s `WithClientSyncEngineAgainstServer`) can build the
 * unified search service without depending on the Koin graph or piercing the `internal` access
 * on [SearchServiceImpl]. Production wiring continues to construct the impl directly inside the
 * books Koin module.
 *
 * The service is left unscoped ([PrincipalProvider.None]) — the harness's ROOT test principal
 * bypasses the access policy, so no per-request scoping is required.
 */
fun createSearchService(
    sqlDb: ListenUpDatabase,
    driver: SqlDriver,
): SearchService = SearchServiceImpl(db = sqlDb, driver = driver)

/**
 * Runs [sql] (with engine-neutral [args] in `?` order) over the driver and maps every row through
 * [rowMapper]. Called inside an open [suspendTransaction], so the raw query runs on the
 * surrounding transaction's connection. Bind indices are 0-based (the SQLDelight JDBC convention).
 */
internal fun <T : Any> SqlDriver.queryList(
    sql: String,
    args: List<Any?>,
    rowMapper: (SqlCursor) -> T,
): List<T> =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val out = mutableListOf<T>()
            while (cursor.next().value) {
                out += rowMapper(cursor)
            }
            QueryResult.Value(out.toList())
        },
        parameters = args.size,
        binders = {
            args.forEachIndexed { index, arg -> bindRaw(index, arg) }
        },
    ).value

/**
 * Runs [sql] (a single-row, single-int-column aggregate, e.g. `COUNT(*)`) over the driver and
 * returns the value, or null if no row. Used for the matched-book count and the facet GROUP BY
 * bucket counts.
 */
internal fun SqlDriver.querySingleInt(
    sql: String,
    args: List<Any?>,
): Int? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val value = if (cursor.next().value) cursor.getLong(0)?.toInt() else null
            QueryResult.Value(value)
        },
        parameters = args.size,
        binders = {
            args.forEachIndexed { index, arg -> bindRaw(index, arg) }
        },
    ).value
