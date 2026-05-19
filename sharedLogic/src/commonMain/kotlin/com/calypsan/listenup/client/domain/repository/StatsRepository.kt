package com.calypsan.listenup.client.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for listening statistics.
 *
 * Computes and provides listening statistics from local data.
 * All stats are derived from listening events stored in Room.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface StatsRepository {
    /**
     * Observe weekly stats (7 days) for the home screen.
     *
     * Emits new values whenever listening events are added or modified.
     *
     * @return Flow emitting HomeStats whenever listening events change
     */
    fun observeWeeklyStats(): Flow<HomeStats>
}

/**
 * Computed stats for the home screen.
 *
 * All stats are computed locally from listening events.
 */
data class HomeStats(
    val totalListenTimeMs: Long,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val dailyListening: List<DailyListening>,
    val genreBreakdown: List<GenreListening>,
)

/**
 * Daily listening summary.
 */
data class DailyListening(
    val date: String,
    val listenTimeMs: Long,
    val booksListened: Int,
)

/**
 * Genre listening breakdown.
 */
data class GenreListening(
    val genreSlug: String,
    val genreName: String,
    val listenTimeMs: Long,
    val percentage: Double,
)
