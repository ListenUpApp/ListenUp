package com.calypsan.listenup.client.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.repository.DailyListening
import com.calypsan.listenup.client.domain.repository.GenreListening
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
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

/**
 * ViewModel for the Home screen stats section.
 *
 * Manages:
 * - 7-day listening chart data
 * - Current/longest streak display
 * - Top 3 genres breakdown
 *
 * Stats are computed locally from ListeningEventEntity records stored in Room.
 * Updates automatically when new listening events are added (local or via SSE).
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
                    totalListenTimeMs = stats.totalListenTimeMs,
                    currentStreakDays = stats.currentStreakDays,
                    longestStreakDays = stats.longestStreakDays,
                    dailyListening = stats.dailyListening,
                    genreBreakdown = stats.genreBreakdown,
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
        val totalListenTimeMs: Long,
        val currentStreakDays: Int,
        val longestStreakDays: Int,
        val dailyListening: List<DailyListening>,
        val genreBreakdown: List<GenreListening>,
    ) : HomeStatsUiState {
        /**
         * Total listening time formatted as human-readable string.
         *
         * Examples: "0m", "45m", "2h 30m", "15h 45m"
         */
        val formattedListenTime: String
            get() {
                val totalMinutes = totalListenTimeMs / MS_PER_MINUTE
                val hours = totalMinutes / MINUTES_PER_HOUR
                val minutes = totalMinutes % MINUTES_PER_HOUR
                return when {
                    hours == 0L -> "${minutes}m"
                    minutes == 0L -> "${hours}h"
                    else -> "${hours}h ${minutes}m"
                }
            }

        /** Whether there is any data to display. */
        val hasData: Boolean
            get() =
                totalListenTimeMs > 0 ||
                    dailyListening.isNotEmpty() ||
                    currentStreakDays > 0 ||
                    longestStreakDays > 0

        /** Whether there is genre data to display. */
        val hasGenreData: Boolean
            get() = genreBreakdown.isNotEmpty()

        /** Maximum daily listening time in milliseconds for chart scaling. */
        val maxDailyListenTimeMs: Long
            get() = dailyListening.maxOfOrNull { it.listenTimeMs } ?: 0

        /** Whether to show the streak section (current or longest streak > 0). */
        val hasStreak: Boolean
            get() = currentStreakDays > 0 || longestStreakDays > 0
    }

    /** Upstream failure — section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : HomeStatsUiState
}
