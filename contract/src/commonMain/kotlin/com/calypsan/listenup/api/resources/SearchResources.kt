package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.SearchService].
 *
 * All search routes live under `/api/v1/search`. The single [All] resource
 * mirrors [com.calypsan.listenup.api.SearchService.search] — a `GET` with
 * [query] and optional [limit] returns a
 * [com.calypsan.listenup.api.dto.SearchResults] envelope containing hits across
 * books, contributors, and series.
 */
@Resource("/api/v1/search")
class SearchResources {
    /**
     * REST mirror of [com.calypsan.listenup.api.SearchService.search] —
     * `GET /api/v1/search?query=…&limit=20` runs a parallel FTS5 query and
     * returns hits across all three domains. A blank [query] responds with
     * empty lists in all categories. [limit] is per-category and clamped
     * server-side to `1..100`. Requires JWT authentication.
     */
    @Resource("")
    class All(
        val parent: SearchResources = SearchResources(),
        val query: String = "",
        val limit: Int = 20,
    )
}
