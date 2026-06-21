package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v19 → v20 — Books-B2a series enrichment.
 *
 * Changes:
 * - `series` — adds the `sortName` column (nullable TEXT). This field existed on
 *   [com.calypsan.listenup.api.sync.SeriesSyncPayload] and in the server-side
 *   `book_series` table from B1, but was never persisted into the Room entity.
 *   B2a adds it so offline sort-by-series works without a server round-trip.
 *
 * All other B2a enrichment fields (`asin`, `description`, `coverPath`,
 * `coverBlurHash`) were already present in the v19 `series` entity — no
 * additional columns are needed.
 *
 * Column name is `sortName` (camelCase) — Room generates this from the entity
 * field name verbatim; verified against the `20.json` Room schema export.
 */
internal val MIGRATION_19_20: Migration =
    object : Migration(19, 20) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `series` ADD COLUMN `sortName` TEXT")
        }
    }
