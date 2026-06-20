package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v33 → v34 — drop the vestigial `isGlobalAccess` column from `collections`.
 *
 * The pure-union visibility rule replaced it: a book is visible iff it belongs to at least one
 * collection the viewer owns or holds an active grant on. No code path reads this column anymore.
 *
 * SQLite 3.35+ supports DROP COLUMN natively; the bundled SQLite driver (2.6.x → SQLite 3.43)
 * satisfies that requirement, so a plain ALTER TABLE is correct here. There is no index on this
 * column, so no index cleanup is needed.
 */
val MIGRATION_33_34: Migration =
    object : Migration(33, 34) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `collections` DROP COLUMN `isGlobalAccess`")
        }
    }
