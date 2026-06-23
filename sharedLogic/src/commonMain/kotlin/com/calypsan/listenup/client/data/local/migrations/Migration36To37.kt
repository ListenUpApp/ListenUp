package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v36 → v37 — add `isSystem` to the `collections` table.
 *
 * Defaults to 0 (false) for all existing rows; the next sync cycle re-stamps the
 * correct value from the server wire payload ([com.calypsan.listenup.api.sync.CollectionSyncPayload]).
 */
internal val MIGRATION_36_37: Migration =
    object : Migration(36, 37) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `collections` ADD COLUMN `isSystem` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
