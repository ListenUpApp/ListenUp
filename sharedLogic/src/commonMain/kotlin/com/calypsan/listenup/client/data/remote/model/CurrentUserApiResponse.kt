package com.calypsan.listenup.client.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for GET /api/v1/users/me response.
 *
 * Contains current authenticated user's profile data.
 */
@Serializable
internal data class CurrentUserApiResponse(
    @SerialName("id")
    val id: String,
    @SerialName("email")
    val email: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    @SerialName("is_root")
    val isRoot: Boolean,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("avatar_type")
    val avatarType: String = "auto",
    @SerialName("avatar_value")
    val avatarValue: String? = null,
    @SerialName("avatar_color")
    val avatarColor: String = "#6B7280",
) {
    fun toDomain(): com.calypsan.listenup.client.data.remote.CurrentUserResponse =
        com.calypsan.listenup.client.data.remote.CurrentUserResponse(
            id = id,
            email = email,
            displayName = displayName,
            firstName = firstName,
            lastName = lastName,
            isRoot = isRoot,
            createdAt = parseTimestamp(createdAt),
            updatedAt = parseTimestamp(updatedAt),
            avatarType = avatarType,
            avatarValue = avatarValue,
            avatarColor = avatarColor,
        )

    private fun parseTimestamp(timestamp: String): Long =
        try {
            kotlin.time.Instant
                .parse(timestamp)
                .toEpochMilliseconds()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }
}
