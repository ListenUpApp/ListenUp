package com.calypsan.listenup.client.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.repository.StatsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
private const val MS_PER_SECOND = 1_000L
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
 * (local or via SSE sync).
 *
 * @property statsRepository Repository for computing local stats
 */
class HomeStatsViewModel(
    private val statsRepository: StatsRepository,
) : ViewModel() {
    val state: StateFlow<HomeStatsUiState> =
        statsRepository
            .observeWeeklyStats()
            .map<_, HomeStatsUiState> { stats ->
                HomeStatsUiState.Ready(
                    totalSecondsThisWeek = stats.totalSecondsThisWeek,
                    currentStreakDays = stats.currentStreakDays,
                    longestStreakDays = stats.longestStreakDays,
                    dailyBuckets = stats.dailyBuckets,
                    topGenres = stats.topGenres,
                    isEverEmpty = stats.isEverEmpty,
                )
            }.onStart { emit(HomeStatsUiState.Loading) }
            .catch { e ->
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                logger.error(e) { "Error observing stats" }
                emit(HomeStatsUiState.Error("Failed to load stats: ${e.message}"))
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = HomeStatsUiState.Loading,
            )

    /** Pull-to-refresh no-op — stats recompute from Room when listening events change. */
    fun refresh() {
        logger.debug { "Refresh requested - stats will update automatically from Room" }
    }
}

/**
 * UI state for the Home stats section.
 *
 * Sealed hierarchy: `Loading` → first `observeWeeklyStats()` emission flips to
 * `Ready`; upstream failures emit `Error`.
 */
sealed interface HomeStatsUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : HomeStatsUiState

    /** Stats loaded from Room. Derived display helpers live here. */
    data class Ready(
        val totalSecondsThisWeek: Long,
        val currentStreakDays: Int,
        val longestStreakDays: Int,
        /** 7 buckets, index 0 = today, index 6 = six days ago. */
        val dailyBuckets: List<DayBucket>,
        /** Up to 3 genres, descending by listening seconds. */
        val topGenres: List<GenreShare>,
        /** True when the user has never listened to anything (not just this week). */
        val isEverEmpty: Boolean,
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

    /** Upstream failure — section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : HomeStatsUiState
}
