package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.UserScopedSyncableTable

/**
 * Storage for per-user materialized listening stats (Playback P2).
 *
 * Extends [UserScopedSyncableTable] — every row carries `user_id` plus the
 * standard sync-discipline columns. One row per user (`id == userId`), enforced
 * by the unique index on `user_id`. The row is updated in place whenever stats
 * are recomputed; rolling-window columns are lazily refreshed on catch-up to
 * handle idle users without a cron.
 */
internal object UserStatsTable : UserScopedSyncableTable("user_stats") {
    val id = varchar("id", 36) // == userId
    val totalSecondsAllTime = long("total_seconds_all_time").default(0L)
    val totalSecondsLast7Days = long("total_seconds_last_7_days").default(0L)
    val totalSecondsLast30Days = long("total_seconds_last_30_days").default(0L)
    val booksStarted = integer("books_started").default(0)
    val booksFinished = integer("books_finished").default(0)
    val currentStreakDays = integer("current_streak_days").default(0)
    val longestStreakDays = integer("longest_streak_days").default(0)
    val lastEventDate = varchar("last_event_date", 10).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_user_stats_user", userId)
    }
}
