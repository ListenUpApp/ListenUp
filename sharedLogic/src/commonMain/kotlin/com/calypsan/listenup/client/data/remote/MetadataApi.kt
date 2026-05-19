@file:Suppress("TooGenericExceptionThrown", "StringLiteralDuplication")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.data.remote.model.ApplyContributorMetadataRequest
import com.calypsan.listenup.client.data.remote.model.ApplyMatchRequest
import com.calypsan.listenup.client.data.remote.model.ContributorMetadataFieldsSelection
import com.calypsan.listenup.client.data.remote.model.ContributorMetadataProfile
import com.calypsan.listenup.client.data.remote.model.ContributorMetadataSearchResponse
import com.calypsan.listenup.client.data.remote.model.ContributorMetadataSearchResult
import com.calypsan.listenup.client.data.remote.model.ContributorResponse
import com.calypsan.listenup.client.data.remote.model.CoverOption
import com.calypsan.listenup.client.data.remote.model.CoverSearchResponse
import com.calypsan.listenup.client.data.remote.model.MetadataBook
import com.calypsan.listenup.client.data.remote.model.MetadataBookResponse
import com.calypsan.listenup.client.data.remote.model.MetadataSearchResponse
import com.calypsan.listenup.client.data.remote.model.MetadataSearchResult
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import com.calypsan.listenup.core.Success

/**
 * Contract interface for metadata API operations.
 *
 * Provides Audible metadata search and book matching functionality.
 * Extracted to enable mocking in tests.
 */
interface MetadataApiContract {
    /**
     * Search Audible for matching audiobooks.
     *
     * @param query Search query (title, author, etc.)
     * @param region Audible region code (default: "us")
     * @return List of matching results
     */
    suspend fun search(
        query: String,
        region: String = "us",
    ): List<MetadataSearchResult>

    /**
     * Get full metadata for a specific Audible book.
     *
     * @param asin Audible Standard Identification Number
     * @param region Audible region code (default: "us")
     * @return Full book metadata
     */
    suspend fun getBook(
        asin: String,
        region: String = "us",
    ): MetadataBook

    /**
     * Apply Audible metadata match to a book.
     *
     * Downloads cover art and updates book metadata from the matched
     * Audible entry.
     *
     * @param bookId Local book ID to update
     * @param request Match request with field selections
     */
    suspend fun applyMatch(
        bookId: String,
        request: ApplyMatchRequest,
    )

    /**
     * Search for cover images from multiple sources (iTunes, Audible).
     *
     * @param title Book title to search for
     * @param author Author name (optional, improves results)
     * @return List of cover options sorted by resolution (highest first)
     */
    suspend fun searchCovers(
        title: String,
        author: String,
    ): List<CoverOption>

    // === Contributor Metadata ===

    /**
     * Search Audible for contributors by name.
     *
     * @param query Contributor name to search
     * @param region Optional Audible region to search
     * @return List of matching contributors
     */
    suspend fun searchContributors(
        query: String,
        region: String? = null,
    ): List<ContributorMetadataSearchResult>

    /**
     * Get contributor profile from Audible.
     *
     * @param asin Audible ASIN
     * @return Contributor profile with biography and image
     */
    suspend fun getContributorProfile(asin: String): ContributorMetadataProfile

    /**
     * Apply Audible metadata to a contributor.
     *
     * @param contributorId Local contributor ID
     * @param asin Audible ASIN of the matched contributor
     * @param imageUrl Image URL from search results (Audible API no longer returns images in profiles)
     * @param applyName Whether to apply the name from Audible
     * @param applyBiography Whether to apply the biography from Audible
     * @param applyImage Whether to download and apply the image
     * @return Result with either success or error
     */
    suspend fun applyContributorMetadata(
        contributorId: String,
        asin: String,
        imageUrl: String?,
        applyName: Boolean,
        applyBiography: Boolean,
        applyImage: Boolean,
    ): ApplyContributorMetadataResult
}

/**
 * Result of applying contributor metadata.
 */
sealed class ApplyContributorMetadataResult {
    /** Metadata applied successfully, includes updated contributor data */
    data class Success(
        val contributor: ContributorResponse,
    ) : ApplyContributorMetadataResult()

    /**
     * Disambiguation required - either multiple matches found or no matches found.
     * If candidates is empty, the user should be prompted to search with a different name.
     *
     * @param candidates List of matching contributors from Audible (may be empty)
     * @param searchedName The name that was searched on Audible
     * @param message Server message explaining the situation
     */
    data class NeedsDisambiguation(
        val candidates: List<ContributorMetadataSearchResult>,
        val searchedName: String? = null,
        val message: String? = null,
    ) : ApplyContributorMetadataResult()

    /** Error occurred */
    data class Error(
        val message: String,
    ) : ApplyContributorMetadataResult()
}

/**
 * API client for Audible metadata operations.
 *
 * Provides search, preview, and match application for enriching
 * local audiobook metadata with Audible information.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class MetadataApi(
    private val clientFactory: ApiClientFactory,
) : MetadataApiContract {
    /**
     * Search Audible for matching audiobooks.
     *
     * Endpoint: GET /api/v1/metadata/search
     */
    override suspend fun search(
        query: String,
        region: String,
    ): List<MetadataSearchResult> {
        val client = clientFactory.getClient()
        val response: ApiResponse<MetadataSearchResponse> =
            client
                .get("/api/v1/metadata/search") {
                    parameter("q", query)
                    parameter("region", region)
                }.body()

        if (!response.success) {
            throw Exception(response.error ?: "Search failed")
        }
        return response.data?.results ?: emptyList()
    }

    /**
     * Get full metadata for a specific Audible book.
     *
     * Endpoint: GET /api/v1/metadata/book/{asin}
     */
    override suspend fun getBook(
        asin: String,
        region: String,
    ): MetadataBook {
        val client = clientFactory.getClient()
        val response: ApiResponse<MetadataBookResponse> =
            client
                .get("/api/v1/metadata/book/$asin") {
                    parameter("region", region)
                }.body()

        if (!response.success) {
            throw Exception(response.error ?: "Failed to get book metadata")
        }
        return response.data?.book ?: throw Exception("No book data in response")
    }

    /**
     * Apply Audible metadata match to a book.
     *
     * Endpoint: POST /api/v1/books/{bookId}/match
     *
     * This endpoint:
     * 1. Fetches full metadata from Audible
     * 2. Downloads and processes cover art
     * 3. Updates book metadata (title, subtitle, narrators, series, etc.)
     * 4. Returns the updated book
     */
    override suspend fun applyMatch(
        bookId: String,
        request: ApplyMatchRequest,
    ) {
        val client = clientFactory.getClient()
        client.post("/api/v1/books/$bookId/match") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /**
     * Search for cover images from multiple sources.
     *
     * Endpoint: GET /api/v1/covers/search
     */
    override suspend fun searchCovers(
        title: String,
        author: String,
    ): List<CoverOption> {
        val client = clientFactory.getClient()
        val response: ApiResponse<CoverSearchResponse> =
            client
                .get("/api/v1/covers/search") {
                    parameter("title", title)
                    parameter("author", author)
                }.body()

        if (!response.success) {
            throw Exception(response.error ?: "Cover search failed")
        }
        return response.data?.covers ?: emptyList()
    }

    // === Contributor Metadata ===

    private val json = appJson

    /**
     * Search Audible for contributors by name.
     *
     * Endpoint: GET /api/v1/metadata/contributors/search
     */
    override suspend fun searchContributors(
        query: String,
        region: String?,
    ): List<ContributorMetadataSearchResult> {
        val client = clientFactory.getClient()
        val response: ApiResponse<ContributorMetadataSearchResponse> =
            client
                .get("/api/v1/metadata/contributors/search") {
                    parameter("q", query)
                    if (region != null) {
                        parameter("region", region)
                    }
                }.body()

        return response.data?.results ?: emptyList()
    }

    /**
     * Get contributor profile from Audible.
     *
     * Endpoint: GET /api/v1/metadata/contributors/{asin}
     */
    override suspend fun getContributorProfile(asin: String): ContributorMetadataProfile {
        val client = clientFactory.getClient()
        val response: ApiResponse<ContributorMetadataProfile> =
            client.get("/api/v1/metadata/contributors/$asin").body()
        return response.data ?: throw Exception("No contributor profile in response")
    }

    /**
     * Apply Audible metadata to a contributor.
     *
     * Endpoint: POST /api/v1/contributors/{id}/metadata
     */
    override suspend fun applyContributorMetadata(
        contributorId: String,
        asin: String,
        imageUrl: String?,
        applyName: Boolean,
        applyBiography: Boolean,
        applyImage: Boolean,
    ): ApplyContributorMetadataResult {
        val client = clientFactory.getClient()
        val request =
            ApplyContributorMetadataRequest(
                asin = asin,
                imageUrl = imageUrl,
                fields =
                    ContributorMetadataFieldsSelection(
                        name = applyName,
                        biography = applyBiography,
                        image = applyImage,
                    ),
            )
        val response =
            client.post("/api/v1/contributors/$contributorId/metadata") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

        return if (response.status == HttpStatusCode.OK) {
            // Parse the updated contributor from response
            val body = response.bodyAsText()
            val apiResponse = json.decodeFromString<ApiResponse<ContributorResponse>>(body)
            val contributor =
                apiResponse.data
                    ?: throw Exception("No contributor data in success response")
            ApplyContributorMetadataResult.Success(contributor)
        } else {
            val errorBody = response.bodyAsText()
            // Try to extract error message from API response envelope
            val errorMessage =
                try {
                    val errorResponse = json.decodeFromString<ApiResponse<Unit>>(errorBody)
                    errorResponse.error ?: "Request failed with status ${response.status}"
                } catch (_: Exception) {
                    // Fallback if parsing fails
                    "Request failed with status ${response.status}"
                }
            ApplyContributorMetadataResult.Error(errorMessage)
        }
    }
}
