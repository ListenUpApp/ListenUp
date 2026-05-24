package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.WeeklyStats
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for listening statistics.
 *
 * Computes and provides listening statistics from local Room data, augmented
 * by server-maintained streak counters from `user_stats`. All computation is
 * local-first: the flow emits without network access and updates reactively as
 * new events land.
 *
 * Part of the domain layer — implementations live in the data layer.
 */
interface StatsRepository {
    /**
     * Observe 7-day listening stats for the home screen.
     *
     * Emits [WeeklyStats.empty] when no user is signed in. Re-emits whenever
     * the local `listening_events` table changes or `user_stats` is updated by
     * sync. Day buckets roll over at local midnight without requiring a
     * manual refresh.
     *
     * @return Flow emitting [WeeklyStats] whenever underlying data changes.
     */
    fun observeWeeklyStats(): Flow<WeeklyStats>
}
