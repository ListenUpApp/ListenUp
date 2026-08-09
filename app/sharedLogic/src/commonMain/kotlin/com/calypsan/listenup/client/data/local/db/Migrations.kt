package com.calypsan.listenup.client.data.local.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * v1 → v2: volume-boost + loudness-normalization columns. Non-destructive: pure `ADD COLUMN`,
 * per the migration policy in [ListenUpDatabase] — the local DB holds the unsynced outbox, so
 * every migration must preserve existing rows.
 *
 * SQL goes through the [executeDdl] seam because `androidx.sqlite`'s raw `execSQL` is absent
 * from the all-targets intersection once a web target exists (see [executeDdl]'s KDoc); the
 * common view of [Migration.migrate] is suspend for the same reason, which is what lets a
 * commonMain migration call it.
 */
internal val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "ALTER TABLE playback_positions ADD COLUMN volumeBoostDb REAL NOT NULL DEFAULT 0",
            )
            connection.executeDdl(
                "ALTER TABLE playback_positions ADD COLUMN hasCustomBoost INTEGER NOT NULL DEFAULT 0",
            )
            connection.executeDdl("ALTER TABLE playback_positions ADD COLUMN measuredGainDb REAL")
            connection.executeDdl(
                "ALTER TABLE user_preferences ADD COLUMN defaultVolumeBoostDb REAL NOT NULL DEFAULT 0",
            )
        }
    }
