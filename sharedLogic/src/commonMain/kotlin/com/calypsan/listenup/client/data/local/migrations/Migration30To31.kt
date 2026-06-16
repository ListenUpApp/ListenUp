package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v30 → v31: rename the activities cache column `createdAt` → `occurredAt` (#548) so the feed
 * orders/displays by the real event time. Renames in place to preserve cached rows.
 */
val MIGRATION_30_31: Migration =
    object : Migration(30, 31) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP INDEX IF EXISTS `index_activities_createdAt`")
            connection.execSQL("ALTER TABLE `activities` RENAME COLUMN `createdAt` TO `occurredAt`")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_activities_occurredAt` ON `activities` (`occurredAt`)",
            )
        }
    }
