package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult

/**
 * Contract interface for user-profile API operations.
 *
 * Retains only the `getCurrentUser` endpoint. The `getBookReaders` and
 * `getUserReadingHistory` endpoints were removed: the Readers section
 * now sources its data from the [com.calypsan.listenup.api.SocialService] RPC
 * (ACL-filtered server-side, refreshed on presence pings), with no REST fallback.
 */
internal interface SessionApiContract {
    /**
     * Get the current authenticated user's profile.
     *
     * Used to fetch user data if missing from local database
     * (e.g., after database was cleared but tokens remain).
     *
     * Endpoint: GET /api/v1/users/me
     * Auth: Required
     *
     * @return Result containing CurrentUserResponse or error
     */
    suspend fun getCurrentUser(): AppResult<CurrentUserResponse>
}

/**
 * Response from GET /api/v1/users/me endpoint.
 */
internal data class CurrentUserResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val firstName: String?,
    val lastName: String?,
    val isRoot: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val avatarType: String = "auto",
    val avatarValue: String? = null,
    val avatarColor: String = "#6B7280",
)
