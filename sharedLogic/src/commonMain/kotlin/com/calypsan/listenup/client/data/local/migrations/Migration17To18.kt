package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v17 → v18 — syncable-domain landing.
 *
 * `playback_positions` becomes a first-class syncable domain. The table gains
 * `revision` (monotonic server revision) and `deletedAt` (epoch-ms tombstone).
 * Existing rows are rebuilt with `revision = 0` and `deletedAt` null — the
 * client's `PlaybackPositionSyncDomainHandler` overwrites them with real values
 * on the first catch-up from the server.
 *
 * The table is rebuilt (create-new → copy → drop → rename) rather than
 * `ALTER TABLE ADD COLUMN`-ed: a NOT-NULL `revision` column needs a constant
 * default to add in place, and the rebuilt schema must match Room's exported
 * v18 schema, which carries no column default.
 */
internal val MIGRATION_17_18: Migration =
    object : Migration(17, 18) {
        override fun migrate(connection: SQLiteConnection) {
            connection.migratePlaybackPositions()
        }
    }

private fun SQLiteConnection.migratePlaybackPositions() {
    execSQL(
        """
        CREATE TABLE `playback_positions_new` (
            `bookId` TEXT NOT NULL,
            `positionMs` INTEGER NOT NULL,
            `playbackSpeed` REAL NOT NULL,
            `hasCustomSpeed` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `syncedAt` INTEGER,
            `lastPlayedAt` INTEGER,
            `isFinished` INTEGER NOT NULL,
            `finishedAt` INTEGER,
            `startedAt` INTEGER,
            `revision` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            PRIMARY KEY(`bookId`)
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO `playback_positions_new` (
            `bookId`, `positionMs`, `playbackSpeed`, `hasCustomSpeed`, `updatedAt`,
            `syncedAt`, `lastPlayedAt`, `isFinished`, `finishedAt`, `startedAt`,
            `revision`, `deletedAt`
        )
        SELECT
            `bookId`, `positionMs`, `playbackSpeed`, `hasCustomSpeed`, `updatedAt`,
            `syncedAt`, `lastPlayedAt`, `isFinished`, `finishedAt`, `startedAt`,
            0, NULL
        FROM `playback_positions`
        """.trimIndent(),
    )
    execSQL("DROP TABLE `playback_positions`")
    execSQL("ALTER TABLE `playback_positions_new` RENAME TO `playback_positions`")
}
