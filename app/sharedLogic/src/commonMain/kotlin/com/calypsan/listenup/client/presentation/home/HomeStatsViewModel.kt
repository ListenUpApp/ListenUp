package com.calypsan.listenup.client.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.WeeklyStats
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.repository.StatsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L

/**
 * ViewModel for the Home screen stats section.
 *
 * Manages:
 * - 7-day listening bar chart (one [DayBucket] per day, today-first)
 * - Current/longest streak display
 * - Top 3 genres breakdown
 *
 * Stats are computed locally from `listening_events` and `user_stats` records
 * stored in Room. Updates automatically when new listening events are added
 * (local or via sync).
 *
 * @property statsRepository Repository for computing local stats
 */
class HomeStatsViewModel(
    private val statsRepository: StatsRepository,
) : ViewModel() {
    val uiState: StateFlow<HomeStatsUiState> =
        statsRepository
            .observeWeeklyStats()
            .map<_, HomeStatsUiState> { stats ->
                if (stats.isEverEmpty) {
                    HomeStatsUiState.Empty
                } else {
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = stats.totalSecondsThisWeek,
                        currentStreakDays = stats.currentStreakDays,
                        longestStreakDays = stats.longestStreakDays,
                        dailyBuckets = stats.dailyBuckets,
                        topGenres = stats.topGenres,
                    )
                }
            }.fallbackTo { e ->
                logger.error(e) { "Error observing stats" }
                HomeStatsUiState.Error(isRetryable = true)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = HomeStatsUiState.Loading,
            )
}

/**
 * UI state for the Home stats section.
 *
 * Sealed hierarchy covering the four distinct surfaces:
 * - [Loading]: pre-first-emission placeholder
 * - [Empty]: user has never listened to anything — shown before any history exists
 * - [Data]: populated stats ready to display
 * - [Error]: upstream failure — section offers a retry action
 *
 * [Empty] is distinct from [Data] with low numbers: it signals the user has
 * no listening history at all, not merely a quiet week.
 */
sealed interface HomeStatsUiState {
    /** Pre-first-emission placeholder shown while the Room query is loading. */
    data object Loading : HomeStatsUiState

    /**
     * User has never listened to anything. Distinct from a user who listened
     * in the past but not this week — [Data] covers that case (with low numbers).
     */
    data object Empty : HomeStatsUiState

    /**
     * Stats loaded and non-empty — user has listening history.
     * Display helpers derived from the raw seconds are computed here so the
     * Composable stays pure transformation-free.
     */
    data class Data(
        val totalSecondsThisWeek: Long,
        val currentStreakDays: Int,
        val longestStreakDays: Int,
        /** 7 buckets, index 0 = today, index 6 = six days ago. */
        val dailyBuckets: List<DayBucket>,
        /** Up to 3 genres, descending by listening seconds. */
        val topGenres: List<GenreShare>,
    ) : HomeStatsUiState {
        /**
         * Total listening time formatted as human-readable string.
         *
         * Examples: "0m", "45m", "2h 30m", "15h 45m"
         */
        val formattedListenTime: String
            get() {
                val totalMinutes = totalSecondsThisWeek / SECONDS_PER_MINUTE
                val hours = totalMinutes / MINUTES_PER_HOUR
                val minutes = totalMinutes % MINUTES_PER_HOUR
                return when {
                    hours == 0L -> "${minutes}m"
                    minutes == 0L -> "${hours}h"
                    else -> "${hours}h ${minutes}m"
                }
            }

        /** Whether there is any listening data to display this week. */
        val hasData: Boolean
            get() = totalSecondsThisWeek > 0 || currentStreakDays > 0 || longestStreakDays > 0

        /** Whether there is genre data to display. */
        val hasGenreData: Boolean
            get() = topGenres.isNotEmpty()

        /** Maximum daily seconds across all buckets — for bar chart scaling. */
        val maxDailySeconds: Long
            get() = dailyBuckets.maxOfOrNull { it.totalSeconds } ?: 0L

        /** Whether to show the streak section (current or longest streak > 0). */
        val hasStreak: Boolean
            get() = currentStreakDays > 0 || longestStreakDays > 0
    }

    /** Upstream failure — section renders an error card. */
    data class Error(
        /** True when the error is transient and the VM will recover on resubscription. */
        val isRetryable: Boolean,
    ) : HomeStatsUiState
}
