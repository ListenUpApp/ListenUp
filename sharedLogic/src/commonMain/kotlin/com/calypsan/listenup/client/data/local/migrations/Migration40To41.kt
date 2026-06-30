package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v40 → v41 — add the `userEditedFields` column to `books`.
 *
 * Mirrors the server's per-field rescan-provenance column: the set of book metadata fields the user
 * has hand-edited (title, subtitle, description, contributors, series), so a later rescan preserves
 * those values instead of re-deriving them from the files. Stored as a comma-joined list of
 * `UserEditedField` names — `TEXT NOT NULL DEFAULT ''` matching the entity's `@ColumnInfo(defaultValue
 * = "''")`, so existing rows migrate to the empty set and Room's post-migration schema validation
 * passes. A fresh install creates the column directly from the entity; only upgrades from v40 run this.
 */
internal val MIGRATION_40_41: Migration =
    object : Migration(40, 41) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `books` ADD COLUMN `userEditedFields` TEXT NOT NULL DEFAULT ''",
            )
        }
    }
