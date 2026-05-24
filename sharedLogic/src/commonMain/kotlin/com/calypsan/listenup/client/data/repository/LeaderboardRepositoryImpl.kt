@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.UserStatsDao
import com.calypsan.listenup.client.data.local.db.UserStatsWithProfile
import com.calypsan.listenup.client.data.local.db.UserWindowAggregate
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone

/**
 * Room-observed, offline-first leaderboard repository.
 *
 * All three category lists ([LeaderboardSnapshot.time], [LeaderboardSnapshot.books],
 * [LeaderboardSnapshot.streak]) are computed in a single upstream Room subscription
 * per period. Switching the active category on the UI is a pure state filter — no
 * additional DB query is triggered.
 *
 * **AllTime:** all three category lists are sourced from `user_stats`, joined with
 * `user_profiles` for display names.
 *
 * **Week/Month/Year:** [time] is sourced from `listening_events` aggregated within
 * the period bounds. [books] and [streak] are empty — per-period book/streak rankings
 * require domain math beyond P3's scope; Time is the headline ranking for bounded periods.
 *
 * Dense ranking: ties share a rank (`[1, 1, 1, 4, 5]`), next distinct value jumps
 * to `(position + 1)`.
 */
class LeaderboardRepositoryImpl(
    private val userStatsDao: UserStatsDao,
    private val listeningEventDao: ListeningEventDao,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : LeaderboardRepository {
    override fun observeSnapshot(
        period: LeaderboardPeriod,
        limit: Int,
    ): Flow<LeaderboardSnapshot> =
        when (period) {
            LeaderboardPeriod.AllTime -> {
                userStatsDao
                    .observeAllJoinedWithProfiles()
                    .map { rows -> snapshotFromUserStats(rows, limit) }
            }

            else -> {
                val (startMs, endMs) = period.bounds(clock.now(), timeZone())
                listeningEventDao
                    .observeUsersWithinWindow(startMs, endMs)
                    .map { aggregates -> snapshotFromWindow(aggregates, limit) }
            }
        }

    // ── All-time path ────────────────────────────────────────────────────────

    /**
     * Builds all three category rankings from the `user_stats` + `user_profiles` join.
     */
    private fun snapshotFromUserStats(
        rows: List<UserStatsWithProfile>,
        limit: Int,
    ): LeaderboardSnapshot {
        val byTime =
            rankDense(
                rows.sortedByDescending { it.stats.totalSecondsAllTime }.take(limit),
            ) { it.stats.totalSecondsAllTime }
                .map { (rank, row) -> rowToEntry(row, rank) }
        val byBooks =
            rankDense(
                rows.sortedByDescending { it.stats.booksFinished }.take(limit),
            ) { it.stats.booksFinished.toLong() }
                .map { (rank, row) -> rowToEntry(row, rank) }
        val byStreak =
            rankDense(
                rows.sortedByDescending { it.stats.longestStreakDays }.take(limit),
            ) { it.stats.longestStreakDays.toLong() }
                .map { (rank, row) -> rowToEntry(row, rank) }
        return LeaderboardSnapshot(byTime, byBooks, byStreak)
    }

    private fun rowToEntry(
        row: UserStatsWithProfile,
        rank: Int,
    ): LeaderboardEntry =
        LeaderboardEntry(
            rank = rank,
            userId = row.stats.id,
            displayName = row.displayName ?: "User",
            totalSeconds = row.stats.totalSecondsAllTime,
            booksFinished = row.stats.booksFinished,
            currentStreakDays = row.stats.currentStreakDays,
            longestStreakDays = row.stats.longestStreakDays,
        )

    // ── Bounded-period path ───────────────────────────────────────────────────

    /**
     * Builds only the [time] ranking from the window aggregate.
     * [books] and [streak] are empty for bounded periods.
     */
    private fun snapshotFromWindow(
        aggregates: List<UserWindowAggregate>,
        limit: Int,
    ): LeaderboardSnapshot {
        val byTime =
            rankDense(aggregates.sortedByDescending { it.totalSeconds }.take(limit)) { it.totalSeconds }
                .map { (rank, agg) ->
                    LeaderboardEntry(
                        rank = rank,
                        userId = agg.userId,
                        displayName = agg.displayName ?: "User",
                        totalSeconds = agg.totalSeconds,
                        booksFinished = 0,
                        currentStreakDays = 0,
                        longestStreakDays = 0,
                    )
                }
        return LeaderboardSnapshot(byTime, emptyList(), emptyList())
    }

    // ── Dense ranking ────────────────────────────────────────────────────────

    /**
     * Assigns dense ranks to a pre-sorted list. Ties share a rank; the next
     * distinct value jumps to `(1-indexed position)`.
     *
     * Example: `[100, 100, 100, 50, 25]` → `[(1, 100), (1, 100), (1, 100), (4, 50), (5, 25)]`.
     *
     * Callers must pass the list **already sorted descending** by [value].
     */
    private fun <T> rankDense(
        sorted: List<T>,
        value: (T) -> Long,
    ): List<Pair<Int, T>> {
        if (sorted.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<Int, T>>()
        var currentRank = 1
        var prevValue: Long? = null
        for ((idx, item) in sorted.withIndex()) {
            val v = value(item)
            if (prevValue == null || v == prevValue) {
                result.add(currentRank to item)
            } else {
                currentRank = idx + 1
                result.add(currentRank to item)
            }
            prevValue = v
        }
        return result
    }
}
