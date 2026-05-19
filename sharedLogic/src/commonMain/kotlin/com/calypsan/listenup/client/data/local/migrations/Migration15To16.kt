package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v15 → v16 — the Scanner Polish scan-warning advisory.
 *
 * Adds the `hasScanWarning` column to `books`: a server-raised per-book advisory
 * that the scan found something worth a human's review. Existing rows default to
 * 0 (no warning); the Books-A sync handler repopulates the value from the wire on
 * the next sync.
 *
 * A plain `ALTER TABLE ... ADD COLUMN` suffices — the column is appended with a
 * `NOT NULL DEFAULT 0`, so no table rebuild is needed.
 */
val MIGRATION_15_16: Migration =
    object : Migration(15, 16) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE books ADD COLUMN hasScanWarning INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
