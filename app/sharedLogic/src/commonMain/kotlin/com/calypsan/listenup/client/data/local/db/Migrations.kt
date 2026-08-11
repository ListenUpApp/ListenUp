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

/**
 * v2 → v3: `books.normalizationGainDb` — the server's tag-read (ReplayGain/iTunNORM) loudness
 * gain, synced down via `BookSyncPayload.normalizationGainDb`. Non-destructive: pure `ADD COLUMN`,
 * per the migration policy in [ListenUpDatabase]. It is the [VolumeGain][com.calypsan.listenup.client.playback.loudness.VolumeGain]
 * fallback input behind the client-measured gain on `playback_positions.measuredGainDb` — until a
 * client measures the book itself, the file's own loudness tag drives normalization instead of 0 dB.
 */
internal val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE books ADD COLUMN normalizationGainDb REAL")
        }
    }

/**
 * v3 → v4: the per-user permission flags the client used to drop on the floor (#1270).
 *
 * `admin_user_roster.canEdit` mirrors the server column added in `V60`, so the admin Users screen
 * can finally show and set the metadata-edit permission `UserPermissionPolicy` has enforced since
 * `V26`. `users.canEdit`/`users.canShare` carry the *signed-in* user's own flags, which
 * `ContractUserMapper` previously collapsed into `isAdmin` alone.
 *
 * `DEFAULT 1` on all three matches both the server column defaults and `UserPermissions`' own
 * defaults, so nobody's effective permissions move: an existing row reads as "may edit, may share"
 * exactly as it did when the flags were absent, and the real values arrive with the next roster
 * sync and the next sign-in respectively. Non-destructive `ADD COLUMN`, per the migration policy
 * in [ListenUpDatabase] — the local DB holds the unsynced outbox.
 */
internal val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "ALTER TABLE admin_user_roster ADD COLUMN canEdit INTEGER NOT NULL DEFAULT 1",
            )
            connection.executeDdl("ALTER TABLE users ADD COLUMN canEdit INTEGER NOT NULL DEFAULT 1")
            connection.executeDdl("ALTER TABLE users ADD COLUMN canShare INTEGER NOT NULL DEFAULT 1")
        }
    }
