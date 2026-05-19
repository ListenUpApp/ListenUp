@file:Suppress("TooGenericExceptionThrown")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Time period for stats queries.
 */
enum class StatsPeriod(
    val value: String,
) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year"),
    ALL("all"),
}

/**
 * Contract interface for stats API operations.
 *
 * Extracted to enable mocking in tests.
 */
interface StatsApiContract {
    /**
     * Get detailed user listening statistics.
     *
     * Returns comprehensive stats including:
     * - Headline numbers (total time, books started/finished)
     * - Daily listening breakdown for charts
     * - Genre distribution
     * - Streak tracking with calendar visualization
     *
     * @param period Time period to aggregate stats for
     * @return Detailed stats response
     */
    suspend fun getUserStats(period: StatsPeriod = StatsPeriod.WEEK): UserStatsDetailedResponse
}

/**
 * API client for listening statistics operations.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class StatsApi(
    private val clientFactory: ApiClientFactory,
) : StatsApiContract {
    /**
     * Get detailed user listening statistics.
     *
     * Endpoint: GET /api/v1/listening/stats?period=week
     *
     * @param period Time period to aggregate stats for
     * @return Detailed stats response
     */
    override suspend fun getUserStats(period: StatsPeriod): UserStatsDetailedResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<UserStatsDetailedResponse> =
            client
                .get("/api/v1/listening/stats") {
                    parameter("period", period.value)
                }.body()

        if (!response.success || response.data == null) {
            throw RuntimeException("Stats API error: ${response.error ?: "Unknown error"}")
        }

        return response.data
    }
}

/**
 * Comprehensive user listening statistics response.
 */
@Serializable
data class UserStatsDetailedResponse(
    /** Query period used */
    @SerialName("period")
    val period: String,
    /** Period start (RFC3339) */
    @SerialName("start_date")
    val startDate: String,
    /** Period end (RFC3339) */
    @SerialName("end_date")
    val endDate: String,
    /** Total listen time in milliseconds */
    @SerialName("total_listen_time_ms")
    val totalListenTimeMs: Long,
    /** Books started in period */
    @SerialName("books_started")
    val booksStarted: Int,
    /** Books finished in period */
    @SerialName("books_finished")
    val booksFinished: Int,
    /** Current listening streak in days */
    @SerialName("current_streak_days")
    val currentStreakDays: Int,
    /** Longest ever streak in days */
    @SerialName("longest_streak_days")
    val longestStreakDays: Int,
    /** Daily listening breakdown for bar charts */
    @SerialName("daily_listening")
    val dailyListening: List<DailyListeningResponse>,
    /** Top genres by listening time */
    @SerialName("genre_breakdown")
    val genreBreakdown: List<GenreListeningResponse>,
    /** Past 12 weeks for streak calendar visualization */
    @SerialName("streak_calendar")
    val streakCalendar: List<StreakDayResponse>? = null,
)

/**
 * Listening activity for a single day.
 */
@Serializable
data class DailyListeningResponse(
    /** Date in YYYY-MM-DD format */
    @SerialName("date")
    val date: String,
    /** Total listen time in milliseconds */
    @SerialName("listen_time_ms")
    val listenTimeMs: Long,
    /** Number of distinct books listened to */
    @SerialName("books_listened")
    val booksListened: Int,
)

/**
 * Genre listening breakdown.
 */
@Serializable
data class GenreListeningResponse(
    /** Genre slug for linking */
    @SerialName("genre_slug")
    val genreSlug: String,
    /** Display name */
    @SerialName("genre_name")
    val genreName: String,
    /** Time spent in milliseconds */
    @SerialName("listen_time_ms")
    val listenTimeMs: Long,
    /** Percentage of total genre listening time (0-100) */
    @SerialName("percentage")
    val percentage: Double,
)

/**
 * Single day in the streak calendar.
 */
@Serializable
data class StreakDayResponse(
    /** Date in YYYY-MM-DD format */
    @SerialName("date")
    val date: String,
    /** Whether minimum listening threshold was met */
    @SerialName("has_listened")
    val hasListened: Boolean,
    /** Total listen time in milliseconds */
    @SerialName("listen_time_ms")
    val listenTimeMs: Long,
    /** Intensity level 0-4 for visual gradient (0=none, 4=max) */
    @SerialName("intensity")
    val intensity: Int,
)
