package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v42 → v43 — add the `coverDownloadedAt` cover-presence marker
 * column to `books`.
 *
 * Upgraders get `NULL` for every existing row; the startup [com.calypsan.listenup.client.data.sync.CoverPresenceReconciler]
 * self-heal backfills the marker from the on-disk covers directory on next launch.
 */
internal val MIGRATION_42_43: Migration =
    object : Migration(42, 43) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `books` ADD COLUMN `coverDownloadedAt` INTEGER")
        }
    }
