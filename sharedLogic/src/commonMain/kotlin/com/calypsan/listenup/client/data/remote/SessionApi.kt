package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.data.remote.model.CurrentUserApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.get

private val logger = KotlinLogging.logger {}

/**
 * API client for user-profile operations.
 *
 * `getBookReaders` and `getUserReadingHistory` were removed: the Readers
 * section now sources its data from the [com.calypsan.listenup.api.SocialService] RPC
 * (ACL-filtered server-side, refreshed on presence pings), with no REST fallback.
 *
 * @property clientFactory Factory for creating authenticated HttpClient.
 */
internal class SessionApi(
    private val clientFactory: ApiClientFactory,
) : SessionApiContract {
    /**
     * Get the current authenticated user's profile.
     *
     * Endpoint: GET /api/v1/users/me
     */
    override suspend fun getCurrentUser(): AppResult<CurrentUserResponse> =
        apiCall(errorMessage = "Current user response missing data") {
            logger.debug { "Fetching current user profile" }
            val client = clientFactory.getClient()
            client.get("/api/v1/users/me").body<ApiResponse<CurrentUserApiResponse>>()
        }.map { it.toDomain() }
}
