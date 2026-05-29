package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Unified full-text search across books, contributors, series, and tags.
 *
 * The implementation runs FTS5 queries in parallel and returns the results in a single
 * [SearchResults] envelope. When [SearchQuery.filters] is active or [SearchQuery.sort]
 * is not [com.calypsan.listenup.api.dto.SearchSort.Relevance], results are scoped to
 * books only (filters/sort are book concepts). An empty or blank query always returns
 * empty lists — it is never an error.
 *
 * REST mirror: [com.calypsan.listenup.api.resources.SearchResources].
 */
@Rpc
interface SearchService {
    /**
     * Runs a search per [query]. Federated across books/contributors/series/tags when
     * [SearchQuery.filters] is inactive and [SearchQuery.sort] is Relevance; books-only
     * otherwise. Blank [SearchQuery.text] returns empty lists.
     */
    suspend fun search(query: SearchQuery): AppResult<SearchResults>
}
