package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v18 → v19 — stats domain landing.
 *
 * Changes:
 * - `listening_events` — rebuilt with the new shape: drops the old leaderboard-era
 *   columns (`deviceId`, `syncState`, `source`, `createdAt`) and replaces them with
 *   the sync-substrate columns (`userId`, `tz`, `deviceLabel`, `revision`, `deletedAt`).
 *   Index set also changes to composite `(userId, endedAt)` / `(userId, revision)` /
 *   `(userId, bookId)`. Existing rows are discarded (pre-launch; no production data at risk).
 * - `user_stats` — rebuilt with the new stats shape: drops the old leaderboard-cache
 *   columns (`userId`, `displayName`, `avatarColor`, `avatarType`, `avatarValue`,
 *   `totalTimeMs`, `totalBooks`, `currentStreak`, `updatedAt`) and replaces with the
 *   materialized stats columns mirroring `UserStatsSyncPayload`. Existing rows discarded.
 * - `tentative_span` — new local-only crash-recovery table (not synced).
 *
 * Both table rebuilds use DROP + CREATE rather than ALTER TABLE because the column sets
 * change incompatibly and the schema must match Room's v19 export exactly.
 */
internal val MIGRATION_18_19: Migration =
    object : Migration(18, 19) {
        override fun migrate(connection: SQLiteConnection) {
            connection.rebuildListeningEvents()
            connection.rebuildUserStats()
            connection.createTentativeSpan()
        }
    }

private fun SQLiteConnection.rebuildListeningEvents() {
    // Drop old indexes first, then the table
    execSQL("DROP INDEX IF EXISTS `index_listening_events_bookId`")
    execSQL("DROP INDEX IF EXISTS `index_listening_events_endedAt`")
    execSQL("DROP INDEX IF EXISTS `index_listening_events_syncState`")
    execSQL("DROP TABLE IF EXISTS `listening_events`")

    execSQL(
        """
        CREATE TABLE IF NOT EXISTS `listening_events` (
            `id` TEXT NOT NULL,
            `userId` TEXT NOT NULL,
            `bookId` TEXT NOT NULL,
            `startPositionMs` INTEGER NOT NULL,
            `endPositionMs` INTEGER NOT NULL,
            `startedAt` INTEGER NOT NULL,
            `endedAt` INTEGER NOT NULL,
            `playbackSpeed` REAL NOT NULL,
            `tz` TEXT NOT NULL,
            `deviceLabel` TEXT,
            `revision` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_listening_events_userId_endedAt` ON `listening_events` (`userId`, `endedAt`)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_listening_events_userId_revision` ON `listening_events` (`userId`, `revision`)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS `index_listening_events_userId_bookId` ON `listening_events` (`userId`, `bookId`)",
    )
}

private fun SQLiteConnection.rebuildUserStats() {
    execSQL("DROP TABLE IF EXISTS `user_stats`")

    execSQL(
        """
        CREATE TABLE IF NOT EXISTS `user_stats` (
            `id` TEXT NOT NULL,
            `totalSecondsAllTime` INTEGER NOT NULL,
            `totalSecondsLast7Days` INTEGER NOT NULL,
            `totalSecondsLast30Days` INTEGER NOT NULL,
            `booksStarted` INTEGER NOT NULL,
            `booksFinished` INTEGER NOT NULL,
            `currentStreakDays` INTEGER NOT NULL,
            `longestStreakDays` INTEGER NOT NULL,
            `lastEventDate` TEXT,
            `revision` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.createTentativeSpan() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS `tentative_span` (
            `id` TEXT NOT NULL,
            `userId` TEXT NOT NULL,
            `bookId` TEXT NOT NULL,
            `startPositionMs` INTEGER NOT NULL,
            `currentPositionMs` INTEGER NOT NULL,
            `startedAt` INTEGER NOT NULL,
            `lastHeartbeatAt` INTEGER NOT NULL,
            `playbackSpeed` REAL NOT NULL,
            `tz` TEXT NOT NULL,
            `deviceLabel` TEXT,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
}
