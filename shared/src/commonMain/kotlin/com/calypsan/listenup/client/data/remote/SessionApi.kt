package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.flatten
import com.calypsan.listenup.client.core.map
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.data.remote.model.BookReadersApiResponse
import com.calypsan.listenup.client.data.remote.model.CurrentUserApiResponse
import com.calypsan.listenup.client.data.remote.model.UserReadingHistoryApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

private val logger = KotlinLogging.logger {}

/**
 * API client for reading session operations.
 *
 * Handles fetching reading history and session data for social features.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class SessionApi(
    private val clientFactory: ApiClientFactory,
) : SessionApiContract {
    /**
     * Get list of readers for a specific book.
     *
     * Endpoint: GET /api/v1/books/{bookId}/readers
     */
    override suspend fun getBookReaders(
        bookId: String,
        limit: Int,
    ): AppResult<BookReadersResponse> =
        suspendRunCatching {
            logger.debug { "Fetching readers for book $bookId (limit=$limit)" }
            val client = clientFactory.getClient()
            val response: ApiResponse<BookReadersApiResponse> =
                client
                    .get("/api/v1/books/$bookId/readers") {
                        parameter("limit", limit)
                    }.body()
            logger.debug { "Fetched readers for book $bookId" }
            response.toResult()
        }.flatten().map { it.toDomain() }

    /**
     * Get the current user's reading history.
     *
     * Endpoint: GET /api/v1/users/me/reading-sessions
     */
    override suspend fun getUserReadingHistory(limit: Int): AppResult<UserReadingHistoryResponse> =
        suspendRunCatching {
            logger.debug { "Fetching user reading history (limit=$limit)" }
            val client = clientFactory.getClient()
            val response: ApiResponse<UserReadingHistoryApiResponse> =
                client
                    .get("/api/v1/users/me/reading-sessions") {
                        parameter("limit", limit)
                    }.body()
            logger.debug { "Fetched ${response.data?.sessions?.size ?: 0} sessions" }
            response.toResult()
        }.flatten().map { it.toDomain() }

    /**
     * Get the current authenticated user's profile.
     *
     * Used to fetch user data if missing from local database.
     *
     * Endpoint: GET /api/v1/users/me
     */
    override suspend fun getCurrentUser(): AppResult<CurrentUserResponse> =
        suspendRunCatching {
            logger.debug { "Fetching current user profile" }
            val client = clientFactory.getClient()
            val response: ApiResponse<CurrentUserApiResponse> =
                client.get("/api/v1/users/me").body()
            logger.debug { "Fetched user: ${response.data?.email}" }
            response.toResult()
        }.flatten().map { it.toDomain() }
}
