@file:Suppress("TooGenericExceptionThrown")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Leaderboard category for ranking.
 */
enum class LeaderboardCategory(
    val value: String,
) {
    TIME("time"),
    BOOKS("books"),
    STREAK("streak"),
}

/**
 * Contract interface for leaderboard API operations.
 *
 * Extracted to enable mocking in tests.
 */
interface LeaderboardApiContract {
    /**
     * Get the community leaderboard.
     *
     * Returns ranked users based on the specified category and period.
     *
     * @param period Time period to aggregate (week, month, year, all)
     * @param category Ranking category (time, books, streak)
     * @param limit Maximum number of entries (default 10)
     * @return Leaderboard response with entries and community stats
     */
    suspend fun getLeaderboard(
        period: StatsPeriod = StatsPeriod.WEEK,
        category: LeaderboardCategory = LeaderboardCategory.TIME,
        limit: Int = 10,
    ): LeaderboardResponse
}

/**
 * API client for leaderboard operations.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class LeaderboardApi(
    private val clientFactory: ApiClientFactory,
) : LeaderboardApiContract {
    /**
     * Get the community leaderboard.
     *
     * Endpoint: GET /api/v1/social/leaderboard
     *
     * @param period Time period to aggregate
     * @param category Ranking category
     * @param limit Maximum entries
     * @return Leaderboard response
     */
    override suspend fun getLeaderboard(
        period: StatsPeriod,
        category: LeaderboardCategory,
        limit: Int,
    ): LeaderboardResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<LeaderboardResponse> =
            client
                .get("/api/v1/social/leaderboard") {
                    parameter("period", period.value)
                    parameter("category", category.value)
                    parameter("limit", limit)
                }.body()

        if (!response.success || response.data == null) {
            throw RuntimeException("Leaderboard API error: ${response.error ?: "Unknown error"}")
        }

        return response.data
    }
}

/**
 * Leaderboard entry representing a single ranked user.
 */
@Serializable
data class LeaderboardEntryResponse(
    @SerialName("rank")
    val rank: Int,
    @SerialName("user_id")
    val userId: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("avatar_type")
    val avatarType: String = "auto",
    @SerialName("avatar_value")
    val avatarValue: String = "",
    @SerialName("avatar_color")
    val avatarColor: String = "#6B7280",
    @SerialName("value")
    val value: Long,
    @SerialName("value_label")
    val valueLabel: String,
    @SerialName("is_current_user")
    val isCurrentUser: Boolean,
    // All-time totals for caching (only included when period=all)
    @SerialName("total_time_ms")
    val totalTimeMs: Long? = null,
    @SerialName("total_books")
    val totalBooks: Int? = null,
    @SerialName("current_streak")
    val currentStreak: Int? = null,
)

/**
 * Community aggregate statistics.
 */
@Serializable
data class CommunityStatsResponse(
    @SerialName("total_time_ms")
    val totalTimeMs: Long,
    @SerialName("total_time_label")
    val totalTimeLabel: String,
    @SerialName("total_books")
    val totalBooks: Int,
    @SerialName("average_streak")
    val averageStreak: Double,
    @SerialName("active_users_count")
    val activeUsersCount: Int,
)

/**
 * Full leaderboard response.
 */
@Serializable
data class LeaderboardResponse(
    @SerialName("category")
    val category: String,
    @SerialName("period")
    val period: String,
    @SerialName("entries")
    val entries: List<LeaderboardEntryResponse>,
    @SerialName("community_stats")
    val communityStats: CommunityStatsResponse,
)
