package com.calypsan.listenup.client.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from GET /api/v1/profile (own profile).
 * Contains basic profile data for the authenticated user.
 */
@Serializable
data class ProfileResponse(
    @SerialName("user_id")
    val userId: String,
    @SerialName("first_name")
    val firstName: String,
    @SerialName("last_name")
    val lastName: String,
    @SerialName("avatar_type")
    val avatarType: String,
    @SerialName("avatar_value")
    val avatarValue: String? = null,
    @SerialName("avatar_color")
    val avatarColor: String,
    @SerialName("tagline")
    val tagline: String? = null,
) {
    /**
     * Computed display name from firstName + lastName.
     */
    val displayName: String
        get() = "$firstName $lastName".trim()
}

/**
 * Request for PATCH /api/v1/profile (update own profile).
 * Only non-null fields are updated (PATCH semantics).
 */
@Serializable
data class UpdateProfileRequest(
    @SerialName("avatar_type")
    val avatarType: String? = null,
    @SerialName("tagline")
    val tagline: String? = null,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    @SerialName("new_password")
    val newPassword: String? = null,
)

/**
 * Response from GET /api/v1/users/{id}/profile (full user profile).
 * Contains complete profile with stats and activity for viewing.
 */
@Serializable
data class FullProfileResponse(
    @SerialName("user_id")
    val userId: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_type")
    val avatarType: String,
    @SerialName("avatar_value")
    val avatarValue: String? = null,
    @SerialName("avatar_color")
    val avatarColor: String,
    @SerialName("tagline")
    val tagline: String? = null,
    @SerialName("total_listen_time_ms")
    val totalListenTimeMs: Long,
    @SerialName("books_finished")
    val booksFinished: Int,
    @SerialName("current_streak")
    val currentStreak: Int,
    @SerialName("longest_streak")
    val longestStreak: Int,
    @SerialName("is_own_profile")
    val isOwnProfile: Boolean,
    @SerialName("recent_books")
    val recentBooks: List<RecentBookResponse> = emptyList(),
    @SerialName("public_shelves")
    val publicShelves: List<ShelfSummaryResponse> = emptyList(),
)

/**
 * Recent book info for profile display.
 */
@Serializable
data class RecentBookResponse(
    @SerialName("book_id")
    val bookId: String,
    @SerialName("title")
    val title: String,
    @SerialName("author_name")
    val authorName: String? = null,
    @SerialName("cover_path")
    val coverPath: String? = null,
    @SerialName("finished_at")
    val finishedAt: String? = null,
)

/**
 * Shelf summary for profile display.
 */
@Serializable
data class ShelfSummaryResponse(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("book_count")
    val bookCount: Int,
)
