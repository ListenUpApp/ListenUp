@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.remote.api

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.appJson
import com.calypsan.listenup.client.core.isDebugBuild
import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.BookApiContract
import com.calypsan.listenup.client.data.remote.BookEditResponse
import com.calypsan.listenup.client.data.remote.BookUpdateRequest
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.ContributorInput
import com.calypsan.listenup.client.data.remote.ContributorSearchResult
import com.calypsan.listenup.client.data.remote.InstanceApiContract
import com.calypsan.listenup.client.data.remote.MergeContributorResponse
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.remote.SeriesEditResponse
import com.calypsan.listenup.client.data.remote.SeriesInput
import com.calypsan.listenup.client.data.remote.SeriesSearchResult
import com.calypsan.listenup.client.data.remote.SeriesUpdateRequest
import com.calypsan.listenup.client.data.remote.UnmergeContributorResponse
import com.calypsan.listenup.client.data.remote.UpdateContributorRequest
import com.calypsan.listenup.client.data.remote.UpdateContributorResponse
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.core.error.AppException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.http.HttpHeaders
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * HTTP client for the ListenUp audiobook server API.
 *
 * Handles both public and authenticated API endpoints:
 * - Public endpoints (like getInstance) use a simple unauthenticated client
 * - Authenticated endpoints use ApiClientFactory for automatic token refresh
 *
 * This separation ensures clean architecture - public endpoints don't carry
 * unnecessary auth overhead, while authenticated endpoints get automatic
 * token management.
 *
 * Uses Ktor 3 for modern, multiplatform HTTP client functionality.
 */
class ListenUpApi(
    private val baseUrl: String,
    private val apiClientFactory: ApiClientFactory? = null,
) : InstanceApiContract,
    BookApiContract,
    ContributorApiContract,
    SeriesApiContract {
    /**
     * Simple HTTP client for public endpoints (no authentication).
     * Used for endpoints like getInstance that don't require credentials.
     */
    private val publicClient =
        HttpClient {
            installListenUpErrorHandling()

            // JSON content negotiation for request/response serialization
            install(ContentNegotiation) {
                json(appJson)
            }

            // HTTP logging. Authorization is always sanitised — the bearer token is
            // sensitive and must never appear in logs, even in debug builds. Verbosity
            // drops in release builds so production log sinks don't receive header dumps.
            // See Finding 04 D6.
            install(Logging) {
                logger =
                    object : Logger {
                        override fun log(message: String) {
                            com.calypsan.listenup.client.data.remote.api.logger
                                .debug { message }
                        }
                    }
                level = if (isDebugBuild) LogLevel.HEADERS else LogLevel.INFO
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }

            // Request timeout configuration
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 90_000
            }

            // Default request configuration
            defaultRequest {
                url(baseUrl)
            }
        }

    /**
     * Get authenticated HTTP client from factory.
     * Used for endpoints that require Bearer token authentication.
     *
     * @throws IllegalStateException if factory not provided
     */
    private suspend fun getAuthenticatedClient(): HttpClient =
        apiClientFactory?.getClient()
            ?: error("ApiClientFactory required for authenticated endpoints")

    /**
     * Fetch the server instance information.
     *
     * This is a public endpoint - no authentication required.
     *
     * @return Result containing the Instance on success, or an error on failure
     */
    override suspend fun getInstance(): AppResult<Instance> =
        suspendRunCatching {
            logger.debug { "Fetching instance information from $baseUrl/api/v1/instance" }

            val response: ApiResponse<Instance> = publicClient.get("/api/v1/instance").body()

            logger.debug { "Received response: success=${response.success}" }

            // Convert API response to Result and extract data
            when (val result = response.toResult()) {
                is Success -> result.data
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Search contributors for autocomplete during book editing.
     *
     * Uses server-side Bleve full-text search for O(log n) performance:
     * - Prefix matching ("bran" → "Brandon Sanderson")
     * - Word matching ("sanderson" in "Brandon Sanderson")
     * - Fuzzy matching for typo tolerance (1 edit distance for 3+ char queries)
     *
     * Endpoint: GET /api/v1/contributors/search?q={query}&limit={limit}
     *
     * @param query Search query (min 2 characters recommended)
     * @param limit Maximum results to return (default 10, max 50)
     * @return Result containing list of matching contributors
     */
    override suspend fun searchContributors(
        query: String,
        limit: Int,
    ): AppResult<List<ContributorSearchResult>> =
        suspendRunCatching {
            logger.debug { "Searching contributors: query='$query', limit=$limit" }

            val client = getAuthenticatedClient()
            val response: ApiResponse<ContributorSearchResponse> =
                client
                    .get("/api/v1/contributors/search") {
                        parameter("q", query)
                        parameter("limit", limit.coerceIn(1, 50))
                    }.body()

            logger.debug { "Received contributor search response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.contributors.map { it.toDomain() }
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Update book metadata (PATCH semantics).
     *
     * Only fields present in the request are updated.
     * Endpoint: PATCH /api/v1/books/{id}
     *
     * @param bookId Book to update
     * @param update Fields to update (null = don't change, empty = clear)
     * @return Result containing the updated book
     */
    override suspend fun updateBook(
        bookId: String,
        update: BookUpdateRequest,
    ): AppResult<BookEditResponse> =
        suspendRunCatching {
            logger.debug { "Updating book: id=$bookId" }

            val client = getAuthenticatedClient()
            val response: ApiResponse<BookEditApiResponse> =
                client
                    .patch("/api/v1/books/$bookId") {
                        contentType(ContentType.Application.Json)
                        setBody(update.toApiRequest())
                    }.body()

            logger.debug { "Received book update response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.toDomain()
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Set book contributors (replaces all existing contributors).
     *
     * Endpoint: PUT /api/v1/books/{id}/contributors
     *
     * @param bookId Book to update
     * @param contributors New list of contributors with roles
     * @return Result containing the updated book
     */
    override suspend fun setBookContributors(
        bookId: String,
        contributors: List<ContributorInput>,
    ): AppResult<BookEditResponse> =
        suspendRunCatching {
            logger.debug { "Setting book contributors: id=$bookId, count=${contributors.size}" }

            val client = getAuthenticatedClient()
            val request =
                SetContributorsApiRequest(
                    contributors = contributors.map { ContributorApiInput(it.name, it.roles) },
                )
            val response: ApiResponse<BookEditApiResponse> =
                client
                    .put("/api/v1/books/$bookId/contributors") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.body()

            logger.debug { "Received set contributors response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.toDomain()
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Search series for autocomplete during book editing.
     *
     * Uses server-side Bleve full-text search for O(log n) performance:
     * - Prefix matching ("mist" → "Mistborn")
     * - Word matching
     * - Fuzzy matching for typo tolerance
     *
     * Endpoint: GET /api/v1/series/search?q={query}&limit={limit}
     *
     * @param query Search query (min 2 characters recommended)
     * @param limit Maximum results to return (default 10, max 50)
     * @return Result containing list of matching series
     */
    override suspend fun searchSeries(
        query: String,
        limit: Int,
    ): AppResult<List<SeriesSearchResult>> =
        suspendRunCatching {
            logger.debug { "Searching series: query='$query', limit=$limit" }

            val client = getAuthenticatedClient()
            val response: ApiResponse<SeriesSearchResponse> =
                client
                    .get("/api/v1/series/search") {
                        parameter("q", query)
                        parameter("limit", limit.coerceIn(1, 50))
                    }.body()

            logger.debug { "Received series search response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.series.map { it.toDomain() }
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Set book series (replaces all existing series relationships).
     *
     * Endpoint: PUT /api/v1/books/{id}/series
     *
     * @param bookId Book to update
     * @param series New list of series with sequence numbers
     * @return Result containing the updated book
     */
    override suspend fun setBookSeries(
        bookId: String,
        series: List<SeriesInput>,
    ): AppResult<BookEditResponse> =
        suspendRunCatching {
            logger.debug { "Setting book series: id=$bookId, count=${series.size}" }

            val client = getAuthenticatedClient()
            val request =
                SetSeriesApiRequest(
                    series = series.map { SeriesApiInput(it.name, it.sequence) },
                )
            val response: ApiResponse<BookEditApiResponse> =
                client
                    .put("/api/v1/books/$bookId/series") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.body()

            logger.debug { "Received set series response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.toDomain()
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Merge a source contributor into a target contributor.
     *
     * The merge operation:
     * - Re-links all books from source to target, preserving original attribution via creditedAs
     * - Adds source's name to target's aliases field
     * - Soft-deletes the source contributor
     *
     * Endpoint: POST /api/v1/contributors/{targetId}/merge
     *
     * @param targetContributorId The contributor to merge into
     * @param sourceContributorId The contributor to merge from (will be soft-deleted)
     * @return Result containing the updated target contributor
     */
    override suspend fun mergeContributor(
        targetContributorId: String,
        sourceContributorId: String,
    ): AppResult<MergeContributorResponse> =
        suspendRunCatching {
            logger.debug { "Merging contributor: source=$sourceContributorId into target=$targetContributorId" }

            val client = getAuthenticatedClient()
            val request = MergeContributorApiRequest(sourceContributorId = sourceContributorId)
            val response: ApiResponse<MergeContributorApiResponse> =
                client
                    .post("/api/v1/contributors/$targetContributorId/merge") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.body()

            logger.debug { "Received merge contributor response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.toDomain()
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Unmerge an alias from a contributor, creating a new separate contributor.
     *
     * POST /api/v1/contributors/{contributorId}/unmerge
     *
     * @param contributorId The contributor to unmerge from
     * @param aliasName The alias to split off as a new contributor
     * @return Result containing the newly created contributor
     */
    override suspend fun unmergeContributor(
        contributorId: String,
        aliasName: String,
    ): AppResult<UnmergeContributorResponse> =
        suspendRunCatching {
            logger.debug { "Unmerging alias '$aliasName' from contributor: $contributorId" }

            val client = getAuthenticatedClient()
            val request = UnmergeContributorApiRequest(aliasName = aliasName)
            val response: ApiResponse<UnmergeContributorApiResponse> =
                client
                    .post("/api/v1/contributors/$contributorId/unmerge") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.body()

            logger.debug { "Received unmerge contributor response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.toDomain()
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Update a contributor's metadata.
     *
     * PUT /api/v1/contributors/{contributorId}
     *
     * @param contributorId The contributor to update
     * @param request The update request containing new field values
     * @return Result containing the updated contributor
     */
    override suspend fun updateContributor(
        contributorId: String,
        request: UpdateContributorRequest,
    ): AppResult<UpdateContributorResponse> =
        suspendRunCatching {
            logger.debug { "Updating contributor: $contributorId" }

            val client = getAuthenticatedClient()
            val apiRequest =
                UpdateContributorApiRequest(
                    name = request.name,
                    biography = request.biography,
                    website = request.website,
                    birthDate = request.birthDate,
                    deathDate = request.deathDate,
                    aliases = request.aliases,
                )
            val response: ApiResponse<UpdateContributorApiResponse> =
                client
                    .put("/api/v1/contributors/$contributorId") {
                        contentType(ContentType.Application.Json)
                        setBody(apiRequest)
                    }.body()

            logger.debug { "Received update contributor response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.toDomain()
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Delete a contributor.
     *
     * Endpoint: DELETE /api/v1/contributors/{id}
     *
     * @param contributorId Contributor to delete
     * @return Result indicating success or failure
     */
    override suspend fun deleteContributor(contributorId: String): AppResult<Unit> =
        suspendRunCatching {
            logger.debug { "Deleting contributor: $contributorId" }

            val client = getAuthenticatedClient()
            val response: ApiResponse<Unit> = client.delete("/api/v1/contributors/$contributorId").body()

            when (val result = response.toResult()) {
                is Success -> logger.debug { "Contributor deleted: $contributorId" }
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Update series metadata (PATCH semantics).
     *
     * Only fields present in the request are updated.
     * Endpoint: PATCH /api/v1/series/{id}
     *
     * @param seriesId Series to update
     * @param request Fields to update (null = don't change, empty = clear)
     * @return Result containing the updated series
     */
    override suspend fun updateSeries(
        seriesId: String,
        request: SeriesUpdateRequest,
    ): AppResult<SeriesEditResponse> =
        suspendRunCatching {
            logger.debug { "Updating series: id=$seriesId" }

            val client = getAuthenticatedClient()
            val response: ApiResponse<SeriesEditApiResponse> =
                client
                    .patch("/api/v1/series/$seriesId") {
                        contentType(ContentType.Application.Json)
                        setBody(request.toApiRequest())
                    }.body()

            logger.debug { "Received series update response: success=${response.success}" }

            when (val result = response.toResult()) {
                is Success -> result.data.toDomain()
                is Failure -> throw AppException(result.error)
            }
        }

    /**
     * Clean up resources when the API client is no longer needed.
     */
    fun close() {
        publicClient.close()
    }
}

/**
 * Internal response model for contributor search endpoint.
 * Maps to server's JSON response structure.
 */
@Serializable
private data class ContributorSearchResponse(
    val contributors: List<ContributorSearchResultResponse>,
)

/**
 * Individual contributor result from search endpoint.
 */
@Serializable
private data class ContributorSearchResultResponse(
    val id: String,
    val name: String,
    @SerialName("book_count")
    val bookCount: Int,
) {
    fun toDomain(): ContributorSearchResult =
        ContributorSearchResult(
            id = id,
            name = name,
            bookCount = bookCount,
        )
}

// --- Book Edit API Models ---

/**
 * API request for PATCH /api/v1/books/{id}.
 * Only non-null fields are sent to the server.
 */
@Serializable
private data class BookUpdateApiRequest(
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    @SerialName("publish_year")
    val publishYear: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val abridged: Boolean? = null,
    @SerialName("series_id")
    val seriesId: String? = null,
    val sequence: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
)

private fun BookUpdateRequest.toApiRequest(): BookUpdateApiRequest =
    BookUpdateApiRequest(
        title = title,
        subtitle = subtitle,
        description = description,
        publisher = publisher,
        publishYear = publishYear,
        language = language,
        isbn = isbn,
        asin = asin,
        abridged = abridged,
        seriesId = seriesId,
        sequence = sequence,
        createdAt = createdAt,
    )

/**
 * API request for PUT /api/v1/books/{id}/contributors.
 */
@Serializable
private data class SetContributorsApiRequest(
    val contributors: List<ContributorApiInput>,
)

@Serializable
private data class ContributorApiInput(
    val name: String,
    val roles: List<String>,
)

/**
 * Internal response model for series search endpoint.
 * Maps to server's JSON response structure.
 */
@Serializable
private data class SeriesSearchResponse(
    val series: List<SeriesSearchResultResponse>,
)

/**
 * Individual series result from search endpoint.
 */
@Serializable
private data class SeriesSearchResultResponse(
    val id: String,
    val name: String,
    @SerialName("book_count")
    val bookCount: Int,
) {
    fun toDomain(): SeriesSearchResult =
        SeriesSearchResult(
            id = id,
            name = name,
            bookCount = bookCount,
        )
}

/**
 * API request for PUT /api/v1/books/{id}/series.
 */
@Serializable
private data class SetSeriesApiRequest(
    val series: List<SeriesApiInput>,
)

@Serializable
private data class SeriesApiInput(
    val name: String,
    val sequence: String?,
)

/**
 * API response for book edit operations.
 * Maps to server's enriched book response structure.
 */
@Serializable
private data class BookEditApiResponse(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    @SerialName("publish_year")
    val publishYear: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val abridged: Boolean = false,
    @SerialName("series_id")
    val seriesId: String? = null,
    @SerialName("series_name")
    val seriesName: String? = null,
    val sequence: String? = null,
    @SerialName("updated_at")
    val updatedAt: String,
) {
    fun toDomain(): BookEditResponse =
        BookEditResponse(
            id = id,
            title = title,
            subtitle = subtitle,
            description = description,
            publisher = publisher,
            publishYear = publishYear,
            language = language,
            isbn = isbn,
            asin = asin,
            abridged = abridged,
            seriesId = seriesId,
            seriesName = seriesName,
            sequence = sequence,
            updatedAt = updatedAt,
        )
}

// --- Merge Contributor API Models ---

/**
 * API request for POST /api/v1/contributors/{id}/merge.
 */
@Serializable
private data class MergeContributorApiRequest(
    @SerialName("source_contributor_id")
    val sourceContributorId: String,
)

/**
 * API response for contributor merge operation.
 * Maps to server's contributor response structure.
 */
@Serializable
private data class MergeContributorApiResponse(
    val id: String,
    val name: String,
    @SerialName("sort_name")
    val sortName: String? = null,
    val biography: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    val asin: String? = null,
    val aliases: List<String> = emptyList(),
    @SerialName("updated_at")
    val updatedAt: String,
) {
    fun toDomain(): MergeContributorResponse =
        MergeContributorResponse(
            id = id,
            name = name,
            sortName = sortName,
            biography = biography,
            imageUrl = imageUrl,
            asin = asin,
            aliases = aliases,
            updatedAt = updatedAt,
        )
}

// --- Unmerge Contributor API Models ---

/**
 * API request for POST /api/v1/contributors/{id}/unmerge.
 */
@Serializable
private data class UnmergeContributorApiRequest(
    @SerialName("alias_name")
    val aliasName: String,
)

/**
 * API response for contributor unmerge operation.
 * Maps to server's contributor response structure.
 */
@Serializable
private data class UnmergeContributorApiResponse(
    val id: String,
    val name: String,
    @SerialName("sort_name")
    val sortName: String? = null,
    val biography: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    val asin: String? = null,
    val aliases: List<String> = emptyList(),
    @SerialName("updated_at")
    val updatedAt: String,
) {
    fun toDomain(): UnmergeContributorResponse =
        UnmergeContributorResponse(
            id = id,
            name = name,
            sortName = sortName,
            biography = biography,
            imageUrl = imageUrl,
            asin = asin,
            aliases = aliases,
            updatedAt = updatedAt,
        )
}

/**
 * API request for PUT /api/v1/contributors/{id}.
 */
@Serializable
private data class UpdateContributorApiRequest(
    val name: String,
    val biography: String? = null,
    val website: String? = null,
    @SerialName("birth_date")
    val birthDate: String? = null,
    @SerialName("death_date")
    val deathDate: String? = null,
    val aliases: List<String> = emptyList(),
)

/**
 * API response for contributor update operation.
 */
@Serializable
private data class UpdateContributorApiResponse(
    val id: String,
    val name: String,
    val biography: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    val website: String? = null,
    @SerialName("birth_date")
    val birthDate: String? = null,
    @SerialName("death_date")
    val deathDate: String? = null,
    val aliases: List<String> = emptyList(),
    @SerialName("updated_at")
    val updatedAt: String,
) {
    fun toDomain(): UpdateContributorResponse =
        UpdateContributorResponse(
            id = id,
            name = name,
            biography = biography,
            imageUrl = imageUrl,
            website = website,
            birthDate = birthDate,
            deathDate = deathDate,
            aliases = aliases,
            updatedAt = updatedAt,
        )
}

// --- Series Edit API Models ---

/**
 * API request for PATCH /api/v1/series/{id}.
 * Only non-null fields are sent to the server.
 */
@Serializable
private data class SeriesUpdateApiRequest(
    val name: String? = null,
    val description: String? = null,
)

private fun SeriesUpdateRequest.toApiRequest(): SeriesUpdateApiRequest =
    SeriesUpdateApiRequest(
        name = name,
        description = description,
    )

/**
 * API response for series edit operations.
 * Maps to server's series response structure.
 */
@Serializable
private data class SeriesEditApiResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("updated_at")
    val updatedAt: String,
) {
    fun toDomain(): SeriesEditResponse =
        SeriesEditResponse(
            id = id,
            name = name,
            description = description,
            updatedAt = updatedAt,
        )
}
