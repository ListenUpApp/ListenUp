package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-observed, offline-first leaderboard backed by the synced `public_profiles`
 * roster (the global social projection). Every user's row is present locally, so all
 * rankings compute client-side with no network call.
 *
 * Time ranks by the period-appropriate rolling-window seconds field (Week=last7,
 * Month=last30, Year=last365, AllTime=allTime). Books and Streak rank by the same
 * period: a bounded period uses the windowed columns (distinct books finished within
 * the window; the longest consecutive-day run within the window), AllTime uses the
 * cumulative totals. The ranked value is carried on the entry so the UI shows it directly.
 *
 * Dense ranking: ties share a rank; the next distinct value jumps to its 1-indexed position.
 */
internal class LeaderboardRepositoryImpl(
    private val publicProfileDao: PublicProfileDao,
) : LeaderboardRepository {
    override fun observeSnapshot(
        period: LeaderboardPeriod,
        limit: Int,
    ): Flow<LeaderboardSnapshot> = publicProfileDao.observeAll().map { rows -> snapshot(rows, period, limit) }

    private fun snapshot(
        rows: List<PublicProfileEntity>,
        period: LeaderboardPeriod,
        limit: Int,
    ): LeaderboardSnapshot {
        val timeSelector: (PublicProfileEntity) -> Long =
            when (period) {
                LeaderboardPeriod.Week -> { e -> e.totalSecondsLast7Days }
                LeaderboardPeriod.Month -> { e -> e.totalSecondsLast30Days }
                LeaderboardPeriod.Year -> { e -> e.totalSecondsLast365Days }
                LeaderboardPeriod.AllTime -> { e -> e.totalSecondsAllTime }
            }

        val time =
            rankDense(rows.sortedByDescending(timeSelector).take(limit), timeSelector)
                .map { (rank, e) -> e.toEntry(rank).copy(totalSeconds = timeSelector(e)) }

        val booksSelector: (PublicProfileEntity) -> Int =
            when (period) {
                LeaderboardPeriod.Week -> { e -> e.booksFinishedLast7Days }
                LeaderboardPeriod.Month -> { e -> e.booksFinishedLast30Days }
                LeaderboardPeriod.Year -> { e -> e.booksFinishedLast365Days }
                LeaderboardPeriod.AllTime -> { e -> e.booksFinished }
            }
        val streakSelector: (PublicProfileEntity) -> Int =
            when (period) {
                LeaderboardPeriod.Week -> { e -> e.longestStreakLast7Days }
                LeaderboardPeriod.Month -> { e -> e.longestStreakLast30Days }
                LeaderboardPeriod.Year -> { e -> e.longestStreakLast365Days }
                LeaderboardPeriod.AllTime -> { e -> e.longestStreakDays }
            }

        val books =
            rankDense(rows.sortedByDescending(booksSelector).take(limit)) { booksSelector(it).toLong() }
                .map { (rank, e) -> e.toEntry(rank).copy(booksFinished = booksSelector(e)) }
        val streak =
            rankDense(rows.sortedByDescending(streakSelector).take(limit)) { streakSelector(it).toLong() }
                .map { (rank, e) -> e.toEntry(rank).copy(longestStreakDays = streakSelector(e)) }

        return LeaderboardSnapshot(time, books, streak)
    }

    private fun PublicProfileEntity.toEntry(rank: Int): LeaderboardEntry =
        LeaderboardEntry(
            rank = rank,
            userId = id,
            displayName = displayName,
            totalSeconds = totalSecondsAllTime,
            booksFinished = booksFinished,
            currentStreakDays = currentStreakDays,
            longestStreakDays = longestStreakDays,
        )

    /**
     * Assigns dense ranks to a pre-sorted list. Ties share a rank; the next distinct
     * value jumps to its 1-indexed position.
     *
     * Example: `[100, 100, 50]` → `[(1, 100), (1, 100), (3, 50)]`.
     *
     * Callers must pass the list already sorted descending by [value].
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
