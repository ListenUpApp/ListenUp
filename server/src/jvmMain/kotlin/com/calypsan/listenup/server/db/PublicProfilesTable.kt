package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/**
 * Global syncable table for the public social profile projection.
 *
 * Extends [SyncableTable] (NOT [UserScopedSyncableTable]) — rows are NOT filtered by
 * the requesting user, so every client's catch-up/firehose receives every user's row.
 * One row per user (`id == userId`). Maintained server-side by
 * `PublicProfileMaintainer`; clients never write it.
 */
internal object PublicProfilesTable : SyncableTable("public_profiles") {
    val id = text("id")
    val displayName = text("display_name")
    val avatarType = text("avatar_type").default("auto")
    val tagline = text("tagline").nullable()
    val totalSecondsAllTime = long("total_seconds_all_time").default(0L)
    val totalSecondsLast7Days = long("total_seconds_last_7_days").default(0L)
    val totalSecondsLast30Days = long("total_seconds_last_30_days").default(0L)
    val totalSecondsLast365Days = long("total_seconds_last_365_days").default(0L)
    val booksFinished = integer("books_finished").default(0)
    val currentStreakDays = integer("current_streak_days").default(0)
    val longestStreakDays = integer("longest_streak_days").default(0)
    override val primaryKey = PrimaryKey(id)
}
