package com.calypsan.listenup.client.data.remote

/**
 * Contract interface for search API operations.
 *
 * Extracted to enable mocking in tests. Production implementation
 * is [SearchApi], test implementation can be a mock or fake.
 */
interface SearchApiContract {
    /**
     * Search across books, contributors, and series.
     *
     * @param query Search query string
     * @param types Comma-separated types to search (book,contributor,series)
     * @param genres Comma-separated genre slugs to filter by
     * @param genrePath Genre path prefix for hierarchical filtering
     * @param minDuration Minimum duration in hours
     * @param maxDuration Maximum duration in hours
     * @param limit Max results to return
     * @param offset Pagination offset
     * @return SearchResponse with hits and facets
     * @throws SearchException on search failure
     */
    suspend fun search(
        query: String,
        types: String? = null,
        genres: String? = null,
        genrePath: String? = null,
        minDuration: Float? = null,
        maxDuration: Float? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): SearchResponse
}
