package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for leaderboard operations.
 *
 * Provides a single [observeSnapshot] that returns all three category rankings
 * in one Room-observed read. Switching categories in the UI costs zero DB queries —
 * the snapshot carries all three lists pre-computed.
 *
 * Part of the domain layer — implementations live in the data layer.
 */
interface LeaderboardRepository {
    /**
     * Observe a leaderboard snapshot for the given [period].
     *
     * Emits a new [LeaderboardSnapshot] whenever the underlying Room rows
     * change. All three category lists ([LeaderboardSnapshot.time],
     * [LeaderboardSnapshot.books], [LeaderboardSnapshot.streak]) are populated
     * in the same emission; callers pick the active list at render time.
     *
     * Within a rank, entries are ordered by display name (case-insensitively), then user id, before
     * [limit] is applied, so the cut is stable and readable.
     *
     * @param period The time range to compute rankings for.
     * @param limit Maximum number of entries per category list.
     * @param currentUserId The signed-in reader, whose entry is marked
     *   [com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry.isYou]; null marks none.
     * @return Reactive flow of leaderboard snapshots.
     */
    fun observeSnapshot(
        period: LeaderboardPeriod,
        limit: Int = 20,
        currentUserId: String?,
    ): Flow<LeaderboardSnapshot>
}
