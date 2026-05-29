package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.SearchService].
 *
 * All search routes live under `/api/v1/search`. The single [All] resource
 * mirrors [com.calypsan.listenup.api.SearchService.search] — a `GET` with
 * [query] and optional filter/sort params returns a
 * [com.calypsan.listenup.api.dto.SearchResults] envelope containing hits across
 * books, contributors, series, and tags.
 */
@Resource("/api/v1/search")
class SearchResources {
    /**
     * REST mirror of [com.calypsan.listenup.api.SearchService.search] —
     * `GET /api/v1/search?query=…&limit=20` runs a parallel FTS5 query and
     * returns hits across all domains. A blank [query] responds with empty lists
     * in all categories. [limit] is per-category and clamped server-side to
     * `1..100`. Requires JWT authentication.
     *
     * Optional book-scoping filters ([genreSlugs], [genrePath], [durationMin],
     * [durationMax], [yearMin], [yearMax]) and [sort] collapse the result to
     * books only when any of them are set.
     */
    @Resource("")
    class All(
        val parent: SearchResources = SearchResources(),
        val query: String = "",
        val limit: Int = 20,
        val genreSlugs: String? = null, // comma-separated
        val genrePath: String? = null,
        val durationMin: Long? = null,
        val durationMax: Long? = null,
        val yearMin: Int? = null,
        val yearMax: Int? = null,
        val sort: String? = null, // SearchSort @SerialName value; see com.calypsan.listenup.api.dto.SearchSort
    )
}
