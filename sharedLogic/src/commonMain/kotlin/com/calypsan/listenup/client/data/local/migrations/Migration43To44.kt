package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v43 → v44 — add the two offline mirror tables that let the Book Detail
 * "readers" section and the "who's listening now" presence surface render (possibly stale) offline
 * instead of blanking on a transient RPC failure. Both are server-fetched caches with an `observedAt`
 * staleness marker; upgraders start empty and the next presence ping fills them.
 *
 * The `CREATE TABLE` statements are copied verbatim from the generated v44 schema so
 * `runMigrationsAndValidate` sees an identical shape.
 */
internal val MIGRATION_43_44: Migration =
    object : Migration(43, 44) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `book_readership` " +
                    "(`bookId` TEXT NOT NULL, `userId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`avatarType` TEXT NOT NULL, `currentProgressPct` INTEGER, `finishesJson` TEXT NOT NULL, " +
                    "`observedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `userId`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `cached_active_sessions` " +
                    "(`userId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `avatarType` TEXT NOT NULL, " +
                    "`bookId` TEXT NOT NULL, `startedAtMs` INTEGER NOT NULL, `observedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`userId`))",
            )
        }
    }
