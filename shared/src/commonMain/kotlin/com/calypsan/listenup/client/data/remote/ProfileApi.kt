package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.remote.model.FullProfileResponse
import com.calypsan.listenup.client.data.remote.model.ProfileResponse
import com.calypsan.listenup.client.data.remote.model.UpdateProfileRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

private val logger = KotlinLogging.logger {}

/**
 * API client for user profile operations.
 *
 * Handles fetching and updating user profiles, including avatar management.
 * Uses ApiClientFactory to obtain authenticated HttpClient at call time.
 *
 * Server endpoints:
 * - GET /api/v1/profile (own profile)
 * - PATCH /api/v1/profile (update own profile)
 * - POST /api/v1/profile/avatar (upload avatar)
 * - GET /api/v1/users/{id}/profile (view any profile)
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class ProfileApi(
    private val clientFactory: ApiClientFactory,
) : ProfileApiContract {
    /**
     * Get the authenticated user's own profile.
     */
    override suspend fun getMyProfile(): AppResult<ProfileResponse> =
        apiCall(errorMessage = "Profile response missing data") {
            logger.debug { "Fetching own profile" }
            val client = clientFactory.getClient()
            client.get("/api/v1/profile").body()
        }

    /**
     * Update the authenticated user's profile.
     */
    override suspend fun updateMyProfile(
        avatarType: String?,
        tagline: String?,
        firstName: String?,
        lastName: String?,
        newPassword: String?,
    ): AppResult<ProfileResponse> =
        apiCall(errorMessage = "Updated profile response missing data") {
            logger.debug {
                "Updating own profile: avatarType=$avatarType, tagline=$tagline, firstName=$firstName, lastName=$lastName"
            }
            val client = clientFactory.getClient()
            val request =
                UpdateProfileRequest(
                    avatarType = avatarType,
                    tagline = tagline,
                    firstName = firstName,
                    lastName = lastName,
                    newPassword = newPassword,
                )
            client
                .patch("/api/v1/profile") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body()
        }

    /**
     * Upload avatar image for the authenticated user.
     */
    override suspend fun uploadAvatar(
        imageData: ByteArray,
        contentType: String,
    ): AppResult<ProfileResponse> =
        apiCall(errorMessage = "Avatar upload response missing data") {
            logger.debug { "Uploading avatar: ${imageData.size} bytes, contentType=$contentType" }
            val client = clientFactory.getClient()
            client
                .post("/api/v1/profile/avatar") {
                    contentType(ContentType.parse(contentType))
                    setBody(imageData)
                }.body()
        }

    /**
     * Get a user's full profile with stats and activity.
     */
    override suspend fun getUserProfile(userId: String): AppResult<FullProfileResponse> =
        apiCall(errorMessage = "User profile response missing data") {
            logger.debug { "Fetching profile for user: $userId" }
            val client = clientFactory.getClient()
            client.get("/api/v1/users/$userId/profile").body()
        }
}
