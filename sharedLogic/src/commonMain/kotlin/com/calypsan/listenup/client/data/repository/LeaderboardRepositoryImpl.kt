@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.local.db.UserLeaderboardStats
import com.calypsan.listenup.client.data.local.db.UserStatsDao
import com.calypsan.listenup.client.data.local.db.UserStatsEntity
import com.calypsan.listenup.client.data.remote.LeaderboardApiContract
import com.calypsan.listenup.client.domain.repository.CommunityStats
import com.calypsan.listenup.client.domain.repository.LeaderboardCategory
import com.calypsan.listenup.client.domain.repository.LeaderboardEntry
import com.calypsan.listenup.client.domain.repository.LeaderboardPeriod
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/** Milliseconds in a day */
private const val MS_PER_DAY = 86_400_000L

/**
 * Offline-first leaderboard data source.
 *
 * Hybrid approach:
 * - Week/Month: Aggregate from activities table (local calculation)
 * - All-time: From user_stats cache (server-provided)
 * - Your stats: Always from listening_events (instant, authoritative)
 *
 * All data flows through Room - no direct API calls for display.
 *
 * @property listeningEventDao DAO for current user's listening events
 * @property activityDao DAO for aggregating others' stats from activities
 * @property userStatsDao DAO for cached all-time user stats
 * @property userDao DAO for current user info
 * @property leaderboardApi API for fetching initial All-time stats
 */
class LeaderboardRepositoryImpl(
    private val listeningEventDao: ListeningEventDao,
    private val activityDao: ActivityDao,
    private val userStatsDao: UserStatsDao,
    private val userDao: UserDao,
    private val leaderboardApi: LeaderboardApiContract,
) : LeaderboardRepository {
    /**
     * Observe leaderboard entries for a given period.
     *
     * Routes to appropriate data source based on period:
     * - Week/Month: Calculate from activities table
     * - All-time: Use cached user_stats from server
     */
    override fun observeLeaderboard(
        period: LeaderboardPeriod,
        category: LeaderboardCategory,
        limit: Int,
    ): Flow<List<LeaderboardEntry>> {
        logger.debug { "Observing leaderboard: period=$period, category=$category" }

        return when (period) {
            LeaderboardPeriod.WEEK, LeaderboardPeriod.MONTH, LeaderboardPeriod.DAY, LeaderboardPeriod.YEAR -> {
                observeFromActivities(period, category, limit)
            }

            LeaderboardPeriod.ALL -> {
                observeFromUserStats(category, limit)
            }
        }
    }

    /**
     * Week/Month/Year: Calculate from activities table.
     * Combines current user's stats from listening_events with
     * others' stats from activities.
     */
    private fun observeFromActivities(
        period: LeaderboardPeriod,
        category: LeaderboardCategory,
        limit: Int,
    ): Flow<List<LeaderboardEntry>> {
        val periodStartMs = periodToEpochMillis(period)

        return combine(
            userDao.observeCurrentUser().filterNotNull(),
            listeningEventDao.observeTotalDurationSince(periodStartMs),
            listeningEventDao.observeDistinctBooksSince(periodStartMs),
            observeMyStreak(),
            activityDao.observeLeaderboardStats(periodStartMs),
        ) { currentUser, myTimeMs, myBooks, myStreak, othersStats ->
            buildLeaderboard(
                currentUser = currentUser,
                myTimeMs = myTimeMs,
                myBooks = myBooks,
                myStreak = myStreak,
                othersStats = othersStats,
                category = category,
                limit = limit,
            )
        }
    }

    /**
     * All-time: Use cached user_stats from server.
     * Combines current user's authoritative local stats with
     * cached stats for other users.
     */
    private fun observeFromUserStats(
        category: LeaderboardCategory,
        limit: Int,
    ): Flow<List<LeaderboardEntry>> =
        combine(
            userDao.observeCurrentUser().filterNotNull(),
            listeningEventDao.observeTotalDurationSince(0), // All time
            listeningEventDao.observeDistinctBooksSince(0),
            observeMyStreak(),
            // TODO(P2-leaderboard): user_stats no longer carries profile data; join with
            //  user_profiles here once the P2 stats sync domain handler lands.
            userStatsDao.observeAll(),
        ) { currentUser, myTimeMs, myBooks, myStreak, cachedStats ->
            // Convert cached stats to the same format as activity-based stats.
            // Profile data (displayName, avatar) not available from user_stats in P2 shape —
            // entries without a matching user_profile entry are omitted until the handler lands.
            val othersStats =
                cachedStats
                    .filter { it.id != currentUser.id.value }
                    .map { it.toLeaderboardStats() }

            buildLeaderboard(
                currentUser = currentUser,
                myTimeMs = myTimeMs,
                myBooks = myBooks,
                myStreak = myStreak,
                othersStats = othersStats,
                category = category,
                limit = limit,
            )
        }

    /**
     * Observe community aggregate stats for a given period.
     */
    override fun observeCommunityStats(period: LeaderboardPeriod): Flow<CommunityStats> =
        if (period == LeaderboardPeriod.ALL) {
            observeCommunityStatsFromUserStats()
        } else {
            observeCommunityStatsFromActivities(period)
        }

    /**
     * Community stats from activities table (Week/Month/Year).
     */
    private fun observeCommunityStatsFromActivities(period: LeaderboardPeriod): Flow<CommunityStats> {
        val periodStartMs = periodToEpochMillis(period)

        return combine(
            userDao.observeCurrentUser().filterNotNull(),
            listeningEventDao.observeTotalDurationSince(periodStartMs),
            listeningEventDao.observeDistinctBooksSince(periodStartMs),
            activityDao.observeCommunityStats(periodStartMs),
        ) { currentUser, myTimeMs, myBooks, communityProjection ->
            // Add current user's stats to community totals
            CommunityStats(
                totalTimeMs = communityProjection.totalTimeMs + myTimeMs,
                totalBooks = communityProjection.totalBooks + myBooks,
                activeUsers = communityProjection.activeUsers + 1, // Include current user
            )
        }
    }

    /**
     * Community stats from cached user_stats (All-time).
     */
    private fun observeCommunityStatsFromUserStats(): Flow<CommunityStats> =
        combine(
            userDao.observeCurrentUser().filterNotNull(),
            listeningEventDao.observeTotalDurationSince(0),
            listeningEventDao.observeDistinctBooksSince(0),
            userStatsDao.observeAll(),
        ) { currentUser, myTimeMs, myBooks, cachedStats ->
            // Sum up all cached stats plus current user's authoritative stats.
            // P2 user_stats stores seconds; convert to ms for CommunityStats.
            val othersRows = cachedStats.filter { it.id != currentUser.id.value }
            val othersTimeMs = othersRows.sumOf { it.totalSecondsAllTime * 1_000L }
            val othersBooks = othersRows.sumOf { it.booksFinished }
            val otherUsersCount = othersRows.size

            CommunityStats(
                totalTimeMs = othersTimeMs + myTimeMs,
                totalBooks = othersBooks + myBooks,
                activeUsers = otherUsersCount + 1, // Include current user
            )
        }

    /**
     * Build the final sorted leaderboard from all data sources.
     */
    private fun buildLeaderboard(
        currentUser: UserEntity,
        myTimeMs: Long,
        myBooks: Int,
        myStreak: Int,
        othersStats: List<UserLeaderboardStats>,
        category: LeaderboardCategory,
        limit: Int,
    ): List<LeaderboardEntry> {
        // Create current user's entry from authoritative local data
        val myEntry =
            LeaderboardEntry(
                rank = 0, // Will be set after sorting
                userId = currentUser.id.value,
                displayName = currentUser.displayName,
                avatarColor = currentUser.avatarColor,
                avatarType = currentUser.avatarType,
                avatarValue = currentUser.avatarValue,
                timeMs = myTimeMs,
                booksCount = myBooks,
                streakDays = myStreak,
                isCurrentUser = true,
            )

        // Convert others' stats to entries (filter out current user if present in othersStats)
        val othersEntries =
            othersStats
                .filter { it.userId != currentUser.id.value }
                .map { stats ->
                    LeaderboardEntry(
                        rank = 0,
                        userId = stats.userId,
                        displayName = stats.displayName,
                        avatarColor = stats.avatarColor,
                        avatarType = stats.avatarType,
                        avatarValue = stats.avatarValue,
                        timeMs = stats.totalTimeMs,
                        booksCount = stats.booksCount,
                        streakDays = 0, // Activities don't have streak info
                        isCurrentUser = false,
                    )
                }

        // Combine, sort by category, assign ranks, and limit
        val allEntries = listOf(myEntry) + othersEntries

        return allEntries
            .sortedByDescending { it.valueFor(category) }
            .take(limit)
            .mapIndexed { index, entry ->
                entry.copy(rank = index + 1)
            }
    }

    /**
     * Observe current user's streak from all listening events.
     * Streak is calculated from all time (no period filter).
     */
    private fun observeMyStreak(): Flow<Int> =
        listeningEventDao
            .observeDistinctDaysSince(0)
            .map { days: List<Long> -> calculateStreak(days) }

    /**
     * Calculate streak from sorted day numbers (descending).
     *
     * A streak requires consecutive calendar days with listening activity.
     * The streak must include today or yesterday to be current.
     */
    private fun calculateStreak(sortedDays: List<Long>): Int {
        if (sortedDays.isEmpty()) return 0

        val today = Clock.System.now().toEpochMilliseconds() / MS_PER_DAY
        val yesterday = today - 1

        // Streak must include today or yesterday
        val mostRecentDay = sortedDays.first()
        if (mostRecentDay < yesterday) return 0

        // Count consecutive days going backwards
        var streak = 0
        var expectedDay = mostRecentDay

        for (day in sortedDays) {
            if (day == expectedDay) {
                streak++
                expectedDay--
            } else if (day < expectedDay) {
                // Gap found, streak ends
                break
            }
            // day > expectedDay shouldn't happen with sorted data, skip
        }

        return streak
    }

    /**
     * Convert period to epoch milliseconds for start of period.
     */
    @Suppress("MagicNumber")
    private fun periodToEpochMillis(period: LeaderboardPeriod): Long {
        val now = Clock.System.now().toEpochMilliseconds()
        return when (period) {
            LeaderboardPeriod.DAY -> now - MS_PER_DAY
            LeaderboardPeriod.WEEK -> now - (7 * MS_PER_DAY)
            LeaderboardPeriod.MONTH -> now - (30 * MS_PER_DAY)
            LeaderboardPeriod.YEAR -> now - (365 * MS_PER_DAY)
            LeaderboardPeriod.ALL -> 0L
        }
    }

    /**
     * Fetch and cache user stats for All-time leaderboard.
     *
     * **Vestigial after Playback-P2.** The `user_stats` table is now populated by
     * [com.calypsan.listenup.client.data.sync.handlers.UserStatsSyncDomainHandler] via the sync
     * engine; the old leaderboard-API fetch path is no longer valid. This method is a no-op
     * until the leaderboard subsystem is rebuilt against the P2 `user_stats` shape.
     * The [leaderboardApi] constructor parameter is also unused as a result.
     */
    @Deprecated(
        message =
            "Vestigial after Playback-P2; the leaderboard subsystem needs a rebuild against " +
                "the P2 user_stats sync domain before this path is meaningful again.",
        level = DeprecationLevel.WARNING,
    )
    override suspend fun fetchAndCacheUserStats(): Boolean {
        logger.debug { "fetchAndCacheUserStats: no-op in P2 — stats arrive via sync domain handler" }
        return true
    }

    /**
     * Check if user stats cache is empty.
     * Used to determine if initial fetch is needed for All-time period.
     */
    override suspend fun isUserStatsCacheEmpty(): Boolean = userStatsDao.count() == 0
}

/**
 * Extension to convert [UserStatsEntity] to [UserLeaderboardStats].
 *
 * Profile fields (displayName, avatar) are not available in the P2 `user_stats` shape —
 * the table stores aggregate stats only. Profile data will be joined from `user_profiles`
 * once the P2 leaderboard integration lands.
 *
 * TODO(P2-leaderboard): join with user_profiles for displayName/avatar.
 */
private fun UserStatsEntity.toLeaderboardStats(): UserLeaderboardStats =
    UserLeaderboardStats(
        userId = id,
        displayName = id, // Profile data not yet in user_stats; use id as placeholder
        avatarColor = "#6B7280",
        avatarType = "auto",
        avatarValue = null,
        totalTimeMs = totalSecondsAllTime * 1_000L,
        booksCount = booksFinished,
    )
