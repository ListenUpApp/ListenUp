package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult

/**
 * Repository contract for search operations.
 *
 * Implements "never stranded" pattern:
 * - Primary: Use server Bleve search (fuzzy, faceted, hierarchical)
 * - Fallback: Local Room FTS5 (simpler but always works)
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface SearchRepository {
    /**
     * Search across books, contributors, and series.
     *
     * @param query Search query string
     * @param types Types to search (null = all)
     * @param genres Genre slugs to filter by
     * @param genrePath Genre path prefix for hierarchical filtering
     * @param limit Max results per type
     * @return SearchResult with hits and metadata
     */
    suspend fun search(
        query: String,
        types: List<SearchHitType>? = null,
        genres: List<String>? = null,
        genrePath: String? = null,
        limit: Int = 20,
    ): SearchResult
}
