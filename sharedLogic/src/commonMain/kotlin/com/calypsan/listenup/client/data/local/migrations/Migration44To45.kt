package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Drops the vestigial `clientOpId` columns from `libraries` and `library_folders`.
 * They were declared "for echo detection" but echo detection is the pending-queue's
 * `containsAndAck`; no code path ever wrote a non-null value.
 */
internal val MIGRATION_44_45: Migration =
    object : Migration(44, 45) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `libraries` DROP COLUMN `clientOpId`")
            connection.execSQL("ALTER TABLE `library_folders` DROP COLUMN `clientOpId`")
        }
    }
