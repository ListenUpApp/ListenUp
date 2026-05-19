@file:Suppress("TooGenericExceptionThrown")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Type of activity in the social feed.
 */
enum class ActivityType(
    val value: String,
) {
    STARTED_BOOK("started_book"),
    FINISHED_BOOK("finished_book"),
    STREAK_MILESTONE("streak_milestone"),
    LISTENING_MILESTONE("listening_milestone"),
    SHELF_CREATED("shelf_created"),
    LISTENING_SESSION("listening_session"),
}

/**
 * Contract interface for activity feed API operations.
 *
 * Extracted to enable mocking in tests.
 */
interface ActivityFeedApiContract {
    /**
     * Get the activity feed.
     *
     * Returns recent community activities, filtered by ACL (only shows
     * activities for books the requesting user can access).
     *
     * @param limit Maximum number of activities to return (default 20)
     * @param before Cursor for pagination (ISO 8601 timestamp)
     * @return Activity feed response with activities
     */
    suspend fun getFeed(
        limit: Int = 20,
        before: String? = null,
    ): ActivityFeedResponse
}

/**
 * API client for activity feed operations.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class ActivityFeedApi(
    private val clientFactory: ApiClientFactory,
) : ActivityFeedApiContract {
    /**
     * Get the activity feed.
     *
     * Endpoint: GET /api/v1/social/feed
     *
     * @param limit Maximum activities to return
     * @param before Pagination cursor
     * @return Activity feed response
     */
    override suspend fun getFeed(
        limit: Int,
        before: String?,
    ): ActivityFeedResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ActivityFeedResponse> =
            client
                .get("/api/v1/social/feed") {
                    parameter("limit", limit)
                    if (before != null) {
                        parameter("before", before)
                    }
                }.body()

        if (!response.success || response.data == null) {
            throw RuntimeException("Activity Feed API error: ${response.error ?: "Unknown error"}")
        }

        return response.data
    }
}

/**
 * Single activity in the feed.
 */
@Serializable
data class ActivityResponse(
    @SerialName("id")
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("type")
    val type: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("user_display_name")
    val userDisplayName: String,
    @SerialName("user_avatar_color")
    val userAvatarColor: String,
    @SerialName("user_avatar_type")
    val userAvatarType: String = "auto",
    @SerialName("user_avatar_value")
    val userAvatarValue: String? = null,
    @SerialName("book_id")
    val bookId: String? = null,
    @SerialName("book_title")
    val bookTitle: String? = null,
    @SerialName("book_author_name")
    val bookAuthorName: String? = null,
    @SerialName("book_cover_path")
    val bookCoverPath: String? = null,
    @SerialName("is_reread")
    val isReread: Boolean = false,
    @SerialName("duration_ms")
    val durationMs: Long = 0,
    @SerialName("milestone_value")
    val milestoneValue: Int = 0,
    @SerialName("milestone_unit")
    val milestoneUnit: String? = null,
    @SerialName("shelf_id")
    val shelfId: String? = null,
    @SerialName("shelf_name")
    val shelfName: String? = null,
)

/**
 * Full activity feed response.
 */
@Serializable
data class ActivityFeedResponse(
    @SerialName("activities")
    val activities: List<ActivityResponse>,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
    @SerialName("has_more")
    val hasMore: Boolean = false,
)
