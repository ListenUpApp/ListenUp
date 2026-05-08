package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.map
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * API client for user settings that sync across devices.
 *
 * Handles fetching and updating user playback settings.
 * Uses ApiClientFactory to obtain authenticated HttpClient at call time.
 *
 * Server endpoint: /api/v1/settings (GET/PATCH)
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class UserPreferencesApi(
    private val clientFactory: ApiClientFactory,
) : UserPreferencesApiContract {
    /**
     * Get user settings from the server.
     *
     * Endpoint: GET /api/v1/settings
     * Auth: Required
     *
     * @return Result containing user settings or error
     */
    override suspend fun getPreferences(): AppResult<UserPreferencesResponse> =
        apiCall(errorMessage = "User preferences response missing data") {
            logger.debug { "Fetching user settings" }
            val client = clientFactory.getClient()
            client.get("/api/v1/settings").body<ApiResponse<UserSettingsApiResponse>>()
        }.map { it.toDomain() }

    /**
     * Update user settings on the server.
     *
     * Endpoint: PATCH /api/v1/settings
     * Auth: Required
     *
     * Uses PATCH semantics - only non-null fields are updated.
     *
     * @param request The settings to update (only non-null fields are sent)
     * @return Result containing updated settings or error
     */
    override suspend fun updatePreferences(request: UserPreferencesRequest): AppResult<UserPreferencesResponse> =
        apiCall(errorMessage = "Updated user preferences response missing data") {
            logger.debug { "Updating user settings: $request" }
            val client = clientFactory.getClient()
            val apiRequest =
                UserSettingsApiRequest(
                    defaultPlaybackSpeed = request.defaultPlaybackSpeed,
                    defaultSkipForwardSec = request.defaultSkipForwardSec,
                    defaultSkipBackwardSec = request.defaultSkipBackwardSec,
                    defaultSleepTimerMin = request.defaultSleepTimerMin,
                    shakeToResetSleepTimer = request.shakeToResetSleepTimer,
                )
            client
                .patch("/api/v1/settings") {
                    contentType(ContentType.Application.Json)
                    setBody(apiRequest)
                }.body<ApiResponse<UserSettingsApiResponse>>()
        }.map { it.toDomain() }
}

/**
 * Internal API response model for user settings.
 * Maps to server's UserSettingsResponse.
 */
@Serializable
internal data class UserSettingsApiResponse(
    @SerialName("default_playback_speed")
    val defaultPlaybackSpeed: Float,
    @SerialName("default_skip_forward_sec")
    val defaultSkipForwardSec: Int,
    @SerialName("default_skip_backward_sec")
    val defaultSkipBackwardSec: Int,
    @SerialName("default_sleep_timer_min")
    val defaultSleepTimerMin: Int? = null,
    @SerialName("shake_to_reset_sleep_timer")
    val shakeToResetSleepTimer: Boolean,
    @SerialName("updated_at")
    val updatedAt: String? = null,
) {
    fun toDomain(): UserPreferencesResponse =
        UserPreferencesResponse(
            defaultPlaybackSpeed = defaultPlaybackSpeed,
            defaultSkipForwardSec = defaultSkipForwardSec,
            defaultSkipBackwardSec = defaultSkipBackwardSec,
            defaultSleepTimerMin = defaultSleepTimerMin,
            shakeToResetSleepTimer = shakeToResetSleepTimer,
        )
}

/**
 * Internal API request model for user settings.
 * Uses PATCH semantics - only non-null fields are sent to server.
 */
@Serializable
internal data class UserSettingsApiRequest(
    @SerialName("default_playback_speed")
    val defaultPlaybackSpeed: Float? = null,
    @SerialName("default_skip_forward_sec")
    val defaultSkipForwardSec: Int? = null,
    @SerialName("default_skip_backward_sec")
    val defaultSkipBackwardSec: Int? = null,
    @SerialName("default_sleep_timer_min")
    val defaultSleepTimerMin: Int? = null,
    @SerialName("shake_to_reset_sleep_timer")
    val shakeToResetSleepTimer: Boolean? = null,
)
