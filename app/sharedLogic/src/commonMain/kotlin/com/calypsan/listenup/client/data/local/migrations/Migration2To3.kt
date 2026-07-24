package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v2 → v3 — extend the contributor FTS index with `sortName` + `aliases` so
 * local contributor search matches pen names and sort forms (parity with what the retired server
 * search offered).
 *
 * The FTS5 virtual tables (`books_fts`/`contributors_fts`/`series_fts`) are a derived cache created
 * by `FtsTableCallback` in `onOpen`, not Room entities, and FTS5 columns cannot be altered in place.
 * So this migration DROPs all three: `onOpen` recreates them with the new schema on the next open,
 * and `FtsPopulator.rebuildIfEmpty` (which checks `books_fts`) detects the empty index and
 * repopulates every table from the local source rows. No content is lost — the FTS is a pure index
 * of Room data, rebuilt from it.
 */
val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS books_fts")
            connection.execSQL("DROP TABLE IF EXISTS contributors_fts")
            connection.execSQL("DROP TABLE IF EXISTS series_fts")
        }
    }
