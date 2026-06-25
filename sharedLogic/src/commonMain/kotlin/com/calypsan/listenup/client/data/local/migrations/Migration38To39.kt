package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v38 → v39 — add `avatarUpdatedAt` to `public_profiles`.
 *
 * Synced from the server projection; signals avatar-bytes changes (so the client force-refreshes
 * the cached image) and doubles as the Coil cache-buster. Existing rows default to 0 until their
 * next sync upsert.
 */
internal val MIGRATION_38_39: Migration =
    object : Migration(38, 39) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `public_profiles` ADD COLUMN `avatarUpdatedAt` INTEGER NOT NULL DEFAULT 0")
        }
    }
