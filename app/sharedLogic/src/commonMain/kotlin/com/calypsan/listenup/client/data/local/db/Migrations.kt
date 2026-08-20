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

/**
 * v4 → v5: the presence cache learns to hold non-live rows.
 *
 * "What Others Are Listening To" used to render live sessions only and hide itself when nobody was
 * listening — which, on a server with a handful of people, is nearly always. A silently absent
 * section is indistinguishable from a broken one, so it now fills with each other person's most
 * recently played book. `cached_active_sessions` therefore stops being a live-sessions table:
 * `startedAtMs` becomes [lastActiveAtMs][com.calypsan.listenup.client.data.local.db.CachedActiveSessionEntity.lastActiveAtMs]
 * (a session start for a live row, a `lastPlayedAt` for a recent one) and an `isLive` discriminator
 * joins it.
 *
 * Both statements are non-destructive `ALTER TABLE`s, per the migration policy in [ListenUpDatabase].
 * This table is only a cache, but it shares a database — and a single migration list — with the
 * unsynced outbox, so a destructive shortcut here would take real writes with it.
 *
 * `DEFAULT 1` is deliberate: every row already cached was written from a live session, because that
 * is all this table could hold. `1` preserves each existing row's meaning exactly, where `0` would
 * relabel everyone as "last seen a while ago" for the moment before the next presence ping replaces
 * the roster wholesale — a brief confident lie in place of a brief absence.
 */
internal val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "ALTER TABLE cached_active_sessions RENAME COLUMN startedAtMs TO lastActiveAtMs",
            )
            connection.executeDdl(
                "ALTER TABLE cached_active_sessions ADD COLUMN isLive INTEGER NOT NULL DEFAULT 1",
            )
        }
    }

/**
 * Schema 6 — `book_series.sequence` becomes a number.
 *
 * It was TEXT, and `BookDao`'s `ORDER BY bs.sequence ASC` therefore sorted it as text: book 10 came
 * before book 2, in every series list, on every client. The column is a mirror of the server's, so
 * the conversion here has to match `V62__series_sequence_numeric.sql` exactly — same guard, same
 * leading-numeric-prefix rule — or a device would disagree with its own server about where a book
 * sits until the next full resync.
 *
 * A bare CAST is not enough: SQLite turns `'Prequel'` into `0.0`, filing an unnumbered volume ahead
 * of book 1 forever. Anything not starting with a digit becomes NULL instead — a wrong number is
 * worse than no number. Values that do start with a digit convert on CAST's leading-prefix rule, so
 * an omnibus `'1-3'` files at book 1.
 *
 * SQLite cannot retype a column, so the table is rebuilt with its keys and indices intact.
 */
internal val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // The doomed copy is renamed aside and the new table created under the real name —
            // and the DROP precedes the index creation, because both indices follow the old table
            // through its rename and would collide on name otherwise.
            connection.executeDdl("ALTER TABLE book_series RENAME TO book_series_old")
            connection.executeDdl(
                """
                CREATE TABLE book_series (
                    bookId TEXT NOT NULL,
                    seriesId TEXT NOT NULL,
                    sequence REAL,
                    PRIMARY KEY (bookId, seriesId),
                    FOREIGN KEY (bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY (seriesId) REFERENCES series(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            connection.executeDdl(
                """
                INSERT INTO book_series (bookId, seriesId, sequence)
                SELECT bookId, seriesId,
                       CASE
                           WHEN sequence IS NULL OR TRIM(sequence) = '' THEN NULL
                           WHEN TRIM(sequence) GLOB '[0-9]*' THEN CAST(TRIM(sequence) AS REAL)
                           ELSE NULL
                       END
                FROM book_series_old
                """.trimIndent(),
            )
            connection.executeDdl("DROP TABLE book_series_old")
            connection.executeDdl("CREATE INDEX index_book_series_bookId ON book_series(bookId)")
            connection.executeDdl("CREATE INDEX index_book_series_seriesId ON book_series(seriesId)")
        }
    }
