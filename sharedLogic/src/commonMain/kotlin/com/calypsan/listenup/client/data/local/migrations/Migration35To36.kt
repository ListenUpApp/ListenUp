package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v35 → v36 — add the six windowed Books/Streak columns to `public_profiles`.
 *
 * These mirror the server's V45 migration: trailing 7/30/365-day distinct-books-finished counts and
 * longest-consecutive-day-streak-within-the-window values, server-computed by `PublicProfileMaintainer`
 * and read by the windowed leaderboard. All `INTEGER NOT NULL DEFAULT 0`, matching the entity's
 * `@ColumnInfo(defaultValue = "0")` so Room's post-migration schema validation passes. A fresh install
 * creates these columns directly from the entity; only upgrades from v35 run this migration.
 */
internal val MIGRATION_35_36: Migration =
    object : Migration(35, 36) {
        override fun migrate(connection: SQLiteConnection) {
            // Room column names are the entity property names (camelCase), NOT snake_case — the
            // server's SQLDelight schema uses snake_case, but the client Room mirror does not.
            listOf(
                "booksFinishedLast7Days",
                "booksFinishedLast30Days",
                "booksFinishedLast365Days",
                "longestStreakLast7Days",
                "longestStreakLast30Days",
                "longestStreakLast365Days",
            ).forEach { column ->
                connection.execSQL(
                    "ALTER TABLE `public_profiles` ADD COLUMN `$column` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
