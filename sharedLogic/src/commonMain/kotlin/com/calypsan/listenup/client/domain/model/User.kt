package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserId

/**
 * Domain model representing a user in the system.
 *
 * This is a pure domain model with no persistence concerns.
 * Used by ViewModels and business logic throughout the app.
 *
 * @property id Unique user identifier
 * @property email User's email address
 * @property displayName User's display name shown in UI
 * @property firstName User's first name (optional)
 * @property lastName User's last name (optional)
 * @property isAdmin Whether user has admin privileges
 * @property avatarType Type of avatar: "auto" for generated, "image" for uploaded
 * @property avatarValue Path to avatar image when type is "image"
 * @property avatarColor Background color for generated avatars (hex format)
 * @property tagline User's profile tagline/bio
 * @property createdAtMs Creation timestamp in epoch milliseconds
 * @property updatedAtMs Last update timestamp in epoch milliseconds
 */
data class User(
    val id: UserId,
    val email: String,
    val displayName: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val isAdmin: Boolean,
    val avatarType: String = "auto",
    val avatarValue: String? = null,
    val avatarColor: String = "#6B7280",
    val tagline: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    /** The user id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value

    /**
     * Returns true if the user has an uploaded avatar image.
     */
    val hasImageAvatar: Boolean
        get() = avatarType == "image" && !avatarValue.isNullOrBlank()

    /**
     * Returns the user's initials for fallback avatar display.
     */
    val initials: String
        get() =
            displayName
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .ifEmpty { displayName.take(1).uppercase() }

    /**
     * Returns the full name if available, otherwise display name.
     */
    val fullName: String
        get() =
            when {
                firstName != null && lastName != null -> "$firstName $lastName"
                firstName != null -> firstName
                lastName != null -> lastName
                else -> displayName
            }
}
