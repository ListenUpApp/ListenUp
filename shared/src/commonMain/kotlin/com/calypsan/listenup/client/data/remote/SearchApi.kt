package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * API client for search endpoints.
 *
 * Provides federated search across books, contributors, and series.
 * Uses server-side Bleve full-text search for fuzzy matching and faceting.
 *
 * Implements [SearchApiContract] for testability - tests can mock the interface
 * without needing to mock HTTP client internals.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class SearchApi(
    private val clientFactory: ApiClientFactory,
) : SearchApiContract {
    /**
     * Search across books, contributors, and series.
     *
     * Endpoint: GET /api/v1/search
     *
     * @param query Search query string
     * @param types Comma-separated types to search (book,contributor,series)
     * @param genres Comma-separated genre slugs to filter by
     * @param genrePath Genre path prefix for hierarchical filtering
     * @param minDuration Minimum duration in hours
     * @param maxDuration Maximum duration in hours
     * @param limit Max results to return
     * @param offset Pagination offset
     */
    override suspend fun search(
        query: String,
        types: String?,
        genres: String?,
        genrePath: String?,
        minDuration: Float?,
        maxDuration: Float?,
        limit: Int,
        offset: Int,
    ): SearchResponse {
        val client = clientFactory.getClient()
        // Search endpoint is wrapped in ApiResponse like all other endpoints
        val response: ApiResponse<SearchResponse> =
            client
                .get("/api/v1/search") {
                    parameter("q", query)
                    types?.let { parameter("types", it) }
                    genres?.let { parameter("genres", it) }
                    genrePath?.let { parameter("genre_path", it) }
                    minDuration?.let { parameter("min_duration", it) }
                    maxDuration?.let { parameter("max_duration", it) }
                    parameter("limit", limit)
                    parameter("offset", offset)
                    parameter("facets", "true")
                }.body()

        logger.info {
            "Search API response: v=${response.version}, success=${response.success}, " +
                "data=${response.data != null}, error=${response.error}"
        }
        if (response.data != null) {
            logger.info {
                "Search data: query=${response.data.query}, total=${response.data.total}, hits=${response.data.hits.size}"
            }
        }

        if (!response.success) {
            throw SearchException(response.error ?: "Search failed: success=false")
        }
        if (response.data == null) {
            throw SearchException("Search failed: data is null")
        }
        return response.data
    }
}

/**
 * Search API response.
 */
@Serializable
data class SearchResponse(
    val query: String,
    val total: Long,
    @SerialName("took_ms")
    val tookMs: Long,
    val hits: List<SearchHitResponse>,
    val facets: SearchFacetsResponse? = null,
)

/**
 * One hit returned by the federated search endpoint. [type] discriminates which fields are
 * populated (e.g. `book` hits carry [author] and [duration]; `series` hits carry [bookCount]).
 */
@Serializable
data class SearchHitResponse(
    val id: String,
    val type: String,
    val score: Float = 0f,
    val name: String,
    val subtitle: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    @SerialName("series_name")
    val seriesName: String? = null,
    val duration: Long? = null,
    @SerialName("book_count")
    val bookCount: Int? = null,
    @SerialName("genre_slugs")
    val genreSlugs: List<String>? = null,
    val tags: List<String>? = null,
    val highlights: Map<String, String>? = null,
)

/** Facet aggregations returned alongside [SearchResponse.hits] when `facets=true` is requested. */
@Serializable
data class SearchFacetsResponse(
    val types: List<FacetCountResponse>? = null,
    val genres: List<FacetCountResponse>? = null,
    val authors: List<FacetCountResponse>? = null,
    val narrators: List<FacetCountResponse>? = null,
)

/** A single facet bucket: [value] is the facet term, [count] is the number of hits in it. */
@Serializable
data class FacetCountResponse(
    val value: String,
    val count: Int,
)

/**
 * Exception thrown when search fails.
 */
class SearchException(
    message: String,
) : Exception(message)
