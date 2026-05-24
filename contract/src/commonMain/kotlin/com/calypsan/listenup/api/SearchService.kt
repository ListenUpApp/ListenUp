package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Unified full-text search across books, contributors, and series.
 *
 * The implementation runs three FTS5 queries in parallel against the
 * `book_search`, `contributor_search`, and `series_search` indexes respectively,
 * then returns the results in a single [SearchResults] envelope.
 *
 * An empty or blank query always returns empty lists — it is never an error.
 *
 * REST mirror: [com.calypsan.listenup.api.resources.SearchResources].
 */
@Rpc
interface SearchService {
    /**
     * Runs a parallel search across books, contributors, and series and returns
     * hits ranked by FTS5 relevance within each category.
     *
     * @param query the user-supplied search string; blank input returns empty lists.
     * @param limit max hits per category, clamped to `1..100`. Default 20.
     */
    suspend fun search(
        query: String,
        limit: Int = 20,
    ): AppResult<SearchResults>
}
