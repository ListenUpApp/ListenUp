package com.calypsan.listenup.client.domain.model

/**
 * Type of search result.
 */
enum class SearchHitType {
    BOOK,
    CONTRIBUTOR,
    SERIES,
    TAG,
}

/**
 * A single search result hit.
 *
 * Contains enough information to render a result card and navigate
 * to the detail screen without additional database queries.
 */
data class SearchHit(
    val id: String,
    val type: SearchHitType,
    val name: String,
    val subtitle: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val seriesName: String? = null,
    val duration: Long? = null,
    val bookCount: Int? = null,
    val genreSlugs: List<String>? = null,
    val tags: List<String>? = null,
    val coverPath: String? = null,
    val score: Float = 0f,
    val highlight: String? = null,
) {
    /**
     * Format duration as human-readable string.
     */
    fun formatDuration(): String? {
        val durationMs = duration ?: return null
        val totalMinutes = durationMs / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

/**
 * Facet count for filtering UI.
 */
data class FacetCount(
    val value: String,
    val count: Int,
)

/**
 * Facets returned with search results.
 *
 * Used to power dynamic filter chips (e.g., "Fantasy (47)").
 */
data class SearchFacets(
    val types: List<FacetCount> = emptyList(),
    val genres: List<FacetCount> = emptyList(),
    val authors: List<FacetCount> = emptyList(),
    val narrators: List<FacetCount> = emptyList(),
)

/**
 * Complete search result.
 */
data class SearchResult(
    val query: String,
    val total: Int,
    val tookMs: Long,
    val hits: List<SearchHit>,
    val facets: SearchFacets = SearchFacets(),
    val isOfflineResult: Boolean = false,
)
