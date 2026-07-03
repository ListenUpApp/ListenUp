package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the `initialScanCompletedAt` column to `libraries` — the server-authoritative signal that a
 * library's first-ever scan has completed, which drives the client's initial-population ("Building
 * your library") gate.
 *
 * Backfill mirrors the server's V51 migration: a library that already holds a live book has
 * effectively finished its initial scan, so its completion time is stamped to `createdAt` (the client
 * analogue of the server's `updated_at`). A returning device thus never re-shows the population screen
 * even before the server's stamped flag syncs down.
 */
internal val MIGRATION_45_46: Migration =
    object : Migration(45, 46) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `libraries` ADD COLUMN `initialScanCompletedAt` INTEGER")
            connection.execSQL(
                "UPDATE `libraries` SET `initialScanCompletedAt` = `createdAt` " +
                    "WHERE `id` IN (SELECT DISTINCT `libraryId` FROM `books` WHERE `deletedAt` IS NULL)",
            )
        }
    }
