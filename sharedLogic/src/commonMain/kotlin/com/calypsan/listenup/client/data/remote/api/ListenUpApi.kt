
package com.calypsan.listenup.client.data.remote.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.core.isDebugBuild
import com.calypsan.listenup.client.data.remote.apiCall
import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.BookApiContract
import com.calypsan.listenup.client.data.remote.BookEditResponse
import com.calypsan.listenup.client.data.remote.BookUpdateRequest
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.ContributorInput
import com.calypsan.listenup.client.data.remote.InstanceApiContract
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.remote.SeriesEditResponse
import com.calypsan.listenup.client.data.remote.SeriesInput
import com.calypsan.listenup.client.data.remote.SeriesUpdateRequest
import com.calypsan.listenup.client.data.remote.UpdateContributorRequest
import com.calypsan.listenup.client.data.remote.UpdateContributorResponse
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.domain.model.Instance
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
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
internal class ListenUpApi(
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
     * @return [AppResult] containing the [Instance] on success, or an error on failure
     */
    override suspend fun getInstance(): AppResult<Instance> =
        apiCall(errorMessage = "Failed to fetch instance info") {
            logger.debug { "Fetching instance information from $baseUrl/api/v1/instance" }
            publicClient.get("/api/v1/instance").body<ApiResponse<Instance>>()
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
        apiCall<BookEditApiResponse>(errorMessage = "Failed to update book $bookId") {
            logger.debug { "Updating book: id=$bookId" }
            val client = getAuthenticatedClient()
            client
                .patch("/api/v1/books/$bookId") {
                    contentType(ContentType.Application.Json)
                    setBody(update.toApiRequest())
                }.body<ApiResponse<BookEditApiResponse>>()
        }.map { it.toDomain() }

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
        apiCall<BookEditApiResponse>(errorMessage = "Failed to set contributors for book $bookId") {
            logger.debug { "Setting book contributors: id=$bookId, count=${contributors.size}" }
            val client = getAuthenticatedClient()
            val request =
                SetContributorsApiRequest(
                    contributors = contributors.map { ContributorApiInput(it.name, it.roles) },
                )
            client
                .put("/api/v1/books/$bookId/contributors") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body<ApiResponse<BookEditApiResponse>>()
        }.map { it.toDomain() }

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
        apiCall<BookEditApiResponse>(errorMessage = "Failed to set series for book $bookId") {
            logger.debug { "Setting book series: id=$bookId, count=${series.size}" }
            val client = getAuthenticatedClient()
            val request =
                SetSeriesApiRequest(
                    series = series.map { SeriesApiInput(it.name, it.sequence) },
                )
            client
                .put("/api/v1/books/$bookId/series") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body<ApiResponse<BookEditApiResponse>>()
        }.map { it.toDomain() }

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
        apiCall<UpdateContributorApiResponse>(errorMessage = "Failed to update contributor $contributorId") {
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
            client
                .put("/api/v1/contributors/$contributorId") {
                    contentType(ContentType.Application.Json)
                    setBody(apiRequest)
                }.body<ApiResponse<UpdateContributorApiResponse>>()
        }.map { it.toDomain() }

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
        apiCall<SeriesEditApiResponse>(errorMessage = "Failed to update series $seriesId") {
            logger.debug { "Updating series: id=$seriesId" }
            val client = getAuthenticatedClient()
            client
                .patch("/api/v1/series/$seriesId") {
                    contentType(ContentType.Application.Json)
                    setBody(request.toApiRequest())
                }.body<ApiResponse<SeriesEditApiResponse>>()
        }.map { it.toDomain() }

    /**
     * Clean up resources when the API client is no longer needed.
     */
    fun close() {
        publicClient.close()
    }
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
    @SerialName("contributors")
    val contributors: List<ContributorApiInput>,
)

@Serializable
private data class ContributorApiInput(
    @SerialName("name")
    val name: String,
    val roles: List<String>,
)

/**
 * API request for PUT /api/v1/books/{id}/series.
 */
@Serializable
private data class SetSeriesApiRequest(
    @SerialName("series")
    val series: List<SeriesApiInput>,
)

@Serializable
private data class SeriesApiInput(
    @SerialName("name")
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
    @SerialName("name")
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
