package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v39 → v40 — add the `user_preferences` table.
 *
 * Local offline-first cache of the user's synced playback preferences (one row per user, keyed by
 * the user ID). Backs live cross-device propagation: a `SyncControl.PreferencesChanged` nudge
 * re-pulls the row, and the UI observes it. The table is empty until the first server fetch writes
 * the caller's row.
 */
internal val MIGRATION_39_40: Migration =
    object : Migration(39, 40) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_preferences` (
                    `id` TEXT NOT NULL,
                    `defaultPlaybackSpeed` REAL NOT NULL,
                    `defaultSkipForwardSec` INTEGER NOT NULL,
                    `defaultSkipBackwardSec` INTEGER NOT NULL,
                    `defaultSleepTimerMin` INTEGER,
                    `shakeToResetSleepTimer` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }
