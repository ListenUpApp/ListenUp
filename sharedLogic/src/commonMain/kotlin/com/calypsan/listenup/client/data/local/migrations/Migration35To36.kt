package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v35 → v36 — add nullable `partTitle` / `bookTitle` to `chapters`.
 *
 * Backs nested chapters: optional Book/Part header labels on the chapter that opens each
 * section. Both columns are nullable; existing rows read back as NULL (flat books, rendered
 * exactly as before). The authoritative form is the exported `36.json` schema. A fresh install
 * creates these columns directly from the entity; only upgrades from v35 run this migration.
 */
internal val MIGRATION_35_36: Migration =
    object : Migration(35, 36) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `chapters` ADD COLUMN `partTitle` TEXT")
            connection.execSQL("ALTER TABLE `chapters` ADD COLUMN `bookTitle` TEXT")
        }
    }
