package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v48 → v49 — add the covering index
 * `index_activities_deletedAt_revision_id` so the sync digest
 * ([com.calypsan.listenup.client.data.local.db.ActivityDao.digestRows]) and the
 * access-gate live-set read (`liveIds`) are index-only scans instead of full
 * scans of the append-forever `activities` table.
 */
internal val MIGRATION_48_49: Migration =
    object : Migration(48, 49) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_activities_deletedAt_revision_id` " +
                    "ON `activities` (`deletedAt`, `revision`, `id`)",
            )
        }
    }
