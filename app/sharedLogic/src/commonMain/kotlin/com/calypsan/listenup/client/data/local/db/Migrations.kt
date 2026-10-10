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

/**
 * Schema 7 — the `notifications` inbox table arrives.
 *
 * A new table for the notifications domain: server-minted inbox rows synced down like any other
 * aggregate, with the event payload stored verbatim as JSON (`eventJson`) so unknown types survive
 * on an older client, a local-first `readAt` stamp, and the standard `revision`/`deletedAt` sync
 * substrate. Non-destructive by construction — pure `CREATE TABLE`/`CREATE INDEX`, touching no
 * existing rows, per the migration policy in [ListenUpDatabase].
 *
 * DDL is copied verbatim from the exported `schemas/…/7.json` `createSql` entries so
 * `runMigrationsAndValidate` sees an identical schema to a fresh v7 install.
 */
internal val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `notifications` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`eventJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "`readAt` INTEGER, `revision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_notifications_deletedAt` ON `notifications` (`deletedAt`)",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_notifications_readAt` ON `notifications` (`readAt`)",
            )
        }
    }

/**
 * v7 → v8: two-tier chapter grouping.
 *
 * `books.bookTierLabel`/`partTierLabel` are the book's own names for its two grouping tiers;
 * `chapters.partTitle`/`bookTitle` are the headers that open each group. All four are nullable
 * with no default — the overwhelmingly common book has a flat chapter list and names nothing, and
 * an unnamed tier must stay distinguishable from one named the empty string.
 */
internal val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `books` ADD COLUMN `bookTierLabel` TEXT")
            connection.executeDdl("ALTER TABLE `books` ADD COLUMN `partTierLabel` TEXT")
            connection.executeDdl("ALTER TABLE `chapters` ADD COLUMN `partTitle` TEXT")
            connection.executeDdl("ALTER TABLE `chapters` ADD COLUMN `bookTitle` TEXT")
        }
    }

/**
 * v8 → v9: the `book_ratings` table arrives.
 *
 * One row per (book, listener): a 2..10 half-star rating plus an optional note, syncing like any
 * other book-scoped junction — mirroring `book_moods`' shape but keyed to the listener instead of
 * a shared tag. Non-destructive by construction — pure `CREATE TABLE`/`CREATE INDEX`, touching no
 * existing rows, per the migration policy in [ListenUpDatabase].
 *
 * DDL is copied verbatim from the exported `schemas/…/9.json` `createSql` entries so
 * `runMigrationsAndValidate` sees an identical schema to a fresh v9 install.
 */
internal val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `book_ratings` (`bookId` TEXT NOT NULL, `userId` TEXT NOT NULL, " +
                    "`syncId` TEXT NOT NULL, `halfStars` INTEGER NOT NULL, `note` TEXT, `ratedAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                    "PRIMARY KEY(`bookId`, `userId`))",
            )
            connection.executeDdl(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_ratings_syncId` ON `book_ratings` (`syncId`)",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_book_ratings_deletedAt` ON `book_ratings` (`deletedAt`)",
            )
        }
    }

/**
 * v9 → v10: the `book_external_ratings` table arrives.
 *
 * One row per (book, outside catalog): the average and count Audible (and later Hardcover/
 * Goodreads) report for a book, mirroring `book_ratings`' shape but with no per-client write path
 * — the server is the sole writer, so there is no outbox for this table. Non-destructive by
 * construction — pure `CREATE TABLE`/`CREATE INDEX`, touching no existing rows, per the migration
 * policy in [ListenUpDatabase].
 *
 * DDL is copied verbatim from the exported `schemas/…/10.json` `createSql` entries so
 * `runMigrationsAndValidate` sees an identical schema to a fresh v10 install.
 */
internal val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `book_external_ratings` (`bookId` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                    "`syncId` TEXT NOT NULL, `average` REAL NOT NULL, `count` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, " +
                    "`revision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`bookId`, `source`))",
            )
            connection.executeDdl(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_external_ratings_syncId` " +
                    "ON `book_external_ratings` (`syncId`)",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_book_external_ratings_deletedAt` " +
                    "ON `book_external_ratings` (`deletedAt`)",
            )
        }
    }

/**
 * v10 → v11: outside ratings from sources an older build could not read come back.
 *
 * PR-2 builds decoded a `source` they did not know (HARDCOVER, GOODREADS) as `UNKNOWN` and hid the
 * row. This build knows them, but the cursored pull never re-sends an unchanged row, so the rows
 * would stay hidden forever. Dropping them and deleting the domain's cursor makes the next
 * catch-up start from `since = 0` (`SyncCatchUpClient.catchUp`) and re-pull every row, decoded.
 * No schema change: the only data touched is server-written and re-fetched, never the outbox.
 */
internal val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("DELETE FROM `book_external_ratings` WHERE `source` = 'UNKNOWN'")
            connection.executeDdl("DELETE FROM `sync_cursor` WHERE `domainName` = 'book_external_ratings'")
        }
    }

/**
 * v11 → v12: `book_readership.hardcoverFinishesJson` — the reads a reader logged on Hardcover (#601 B3),
 * cached beside their ListenUp finishes so the Readers section badges them offline too. Pure
 * `ADD COLUMN` with an empty default, per the migration policy in [ListenUpDatabase]: a cached reader
 * simply has no Hardcover reads until the next readership refresh.
 */
internal val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `book_readership` ADD COLUMN `hardcoverFinishesJson` TEXT NOT NULL DEFAULT ''")
        }
    }

/**
 * v12 → v13: the inert "Can share" permission leaves the local mirror — `users.canShare` and
 * `admin_user_roster.canShare` are dropped, mirroring the server's `V82__drop_can_share.sql`.
 *
 * Since only admins write collections (#1548) the flag gated nothing, so no client reads it any
 * more. This is a column drop, but not a data loss: every row survives, and the dropped values had
 * no effect anywhere. The unsynced outbox lives in other tables and is untouched, per the migration
 * policy in [ListenUpDatabase]. Neither column is indexed or keyed, so SQLite's `DROP COLUMN`
 * (bundled SQLite on every platform) applies directly — no table rebuild needed.
 */
internal val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `users` DROP COLUMN `canShare`")
            connection.executeDdl("ALTER TABLE `admin_user_roster` DROP COLUMN `canShare`")
        }
    }

/**
 * v13 → v14: `book_external_ratings.fetchedAt` — when the server last fetched each outside rating, so
 * Book Detail can say how fresh it is ("Updated 3 days ago"). A nullable `ADD COLUMN`, per the migration
 * policy in [ListenUpDatabase]. Rows mirrored before it have no time, so this also rewinds that
 * domain's cursor, as [MIGRATION_10_11] did: a missing cursor is `since = 0`, and the next catch-up
 * re-pulls every row with its time. The domain has no outbox, so nothing unsynced is touched.
 */
internal val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `book_external_ratings` ADD COLUMN `fetchedAt` INTEGER")
            connection.executeDdl("DELETE FROM `sync_cursor` WHERE `domainName` = 'book_external_ratings'")
        }
    }

/**
 * v14 → v15: `book_readership.finishesAlsoOnHardcoverJson` — which of a reader's finishes were also
 * logged on Hardcover, in a column of its own. #1567 shipped the flag as a `:hardcover` suffix inside
 * `finishesJson` (`900:hardcover,300`) to stay off a schema bump; this moves it out.
 *
 * The readership mirror has no sync cursor to rewind (it is replaced wholesale per book on each presence
 * ping), so rather than blank it the migration converts in place: split each `finishesJson` on commas,
 * keep the suffixed tokens (suffix stripped) as the new column, then strip every suffix from
 * `finishesJson`. Cached readers keep every finish and every flag offline. SQL only, because `prepare`
 * is not callable from common code (see [executeDdl]); the table holds no outbox rows.
 */
internal val MIGRATION_14_15 =
    object : Migration(14, 15) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "ALTER TABLE `book_readership` ADD COLUMN `finishesAlsoOnHardcoverJson` TEXT NOT NULL DEFAULT ''",
            )
            connection.executeDdl(
                """
                UPDATE `book_readership` SET `finishesAlsoOnHardcoverJson` = COALESCE((
                    WITH RECURSIVE finish(token, rest) AS (
                        SELECT '', `book_readership`.`finishesJson` || ','
                        UNION ALL
                        SELECT substr(rest, 1, instr(rest, ',') - 1), substr(rest, instr(rest, ',') + 1)
                        FROM finish WHERE rest <> ''
                    )
                    SELECT group_concat(replace(token, ':hardcover', ''), ',')
                    FROM finish WHERE token LIKE '%:hardcover'
                ), '')
                WHERE `finishesJson` LIKE '%:hardcover%'
                """.trimIndent(),
            )
            connection.executeDdl(
                "UPDATE `book_readership` SET `finishesJson` = replace(`finishesJson`, ':hardcover', '') " +
                    "WHERE `finishesJson` LIKE '%:hardcover%'",
            )
        }
    }

/**
 * v15 → v16: `series.parentId` / `series.parentPosition` — the series tree (#962), mirroring the
 * server's `V85__series_hierarchy.sql`. Two `ADD COLUMN`s and an index, per the migration policy in
 * [ListenUpDatabase], so every cached series starts out as a root.
 *
 * It also deletes the `series` sync cursor. A build that predates the hierarchy decoded a
 * re-parented series without its `parentId`, yet stored the bumped revision and advanced its
 * cursor — and the cursored pull never re-sends an unchanged row, so those series would stay
 * roots forever. Without a cursor the next catch-up starts from `since = 0`
 * (`SyncCatchUpClient.catchUp`) and re-pulls every series. The rows keep their revisions: the
 * re-pulled payloads arrive at the revision already stored, which
 * [com.calypsan.listenup.client.data.sync.domains.RevisionGuard] lets through (only a strictly
 * older revision is stale). Server-written data only; the outbox is untouched.
 */
internal val MIGRATION_15_16 =
    object : Migration(15, 16) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `series` ADD COLUMN `parentId` TEXT")
            connection.executeDdl("ALTER TABLE `series` ADD COLUMN `parentPosition` INTEGER")
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_series_parentId` ON `series` (`parentId`)")
            connection.executeDdl("DELETE FROM `sync_cursor` WHERE `domainName` = 'series'")
        }
    }

/**
 * v16 → v17: `libraries.metadataRegion` — the library's Audible store, so matching starts a search there.
 * A nullable `ADD COLUMN`, per the migration policy in [ListenUpDatabase]. Rows mirrored before it have no
 * store, so this rewinds the `libraries` cursor, as [MIGRATION_15_16] did for its domain: the next
 * catch-up re-pulls the library with its store. The domain is online-only, so nothing unsynced is touched.
 */
internal val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `libraries` ADD COLUMN `metadataRegion` TEXT")
            connection.executeDdl("DELETE FROM `sync_cursor` WHERE `domainName` = 'libraries'")
        }
    }

/**
 * v17 → v18: `books.lastMatch` — the book's live metadata match (its receipt), mirrored from
 * `BookSyncPayload.lastMatch` so Book Detail can offer "Undo last match" offline. A nullable `ADD COLUMN`, per
 * the migration policy in [ListenUpDatabase].
 *
 * Unlike [MIGRATION_16_17] it does not rewind the `books` cursor: that would re-pull the whole library, and the
 * only thing it could recover is a match applied on another device while this one ran an older build — whose
 * Undo row then simply doesn't show here, never a wrong one. Every later book change carries the field.
 */
internal val MIGRATION_17_18 =
    object : Migration(17, 18) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `books` ADD COLUMN `lastMatch` TEXT")
        }
    }

/**
 * v18 → v19: `users.canCurateLibrary` and `admin_user_roster.canCurateLibrary` — the Curate library
 * permission split out of Edit metadata. Each is backfilled from `canEdit`, exactly as the server's V92
 * backfills `can_curate_library` from `can_edit`, so a mirrored row already agrees with the server and
 * no cursor needs rewinding. A row synced afterwards carries the real value.
 */
internal val MIGRATION_18_19 =
    object : Migration(18, 19) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `users` ADD COLUMN `canCurateLibrary` INTEGER NOT NULL DEFAULT 0")
            connection.executeDdl("UPDATE `users` SET `canCurateLibrary` = `canEdit`")
            connection.executeDdl("ALTER TABLE `admin_user_roster` ADD COLUMN `canCurateLibrary` INTEGER NOT NULL DEFAULT 0")
            connection.executeDdl("UPDATE `admin_user_roster` SET `canCurateLibrary` = `canEdit`")
        }
    }

/**
 * v19 → v20: Story World. The `entities` mirror (the access-gated, outbox-backed `entities` sync domain); the
 * Contribute and Curate Story World flags on `users` and `admin_user_roster`, at the server's own column
 * defaults (contribute on, curate off) — which every user holds until an admin changes one, so a mirrored row
 * already agrees with the server and no cursor needs rewinding; and the outbox's
 * `pending_operation.mayHaveLanded` flag, which lets an undo withdraw a Delete that a pre-send failure parked.
 * Pure CREATE / ADD COLUMN, per the migration policy in [ListenUpDatabase] — every queued op survives. Existing
 * ops start at `mayHaveLanded = 0`: only the new `entities` channel ever asks, and none of its ops can predate
 * this version. The new domain has no cursor yet, so its first catch-up starts from zero by itself.
 */
internal val MIGRATION_19_20 =
    object : Migration(19, 20) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `entities` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`descriptor` TEXT, `parentId` TEXT, `homeSeriesId` TEXT, `homeBookId` TEXT, `imageRef` TEXT, " +
                    "`createdBy` TEXT, `updatedBy` TEXT, `revision` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_entities_homeSeriesId` ON `entities` (`homeSeriesId`)")
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_entities_homeBookId` ON `entities` (`homeBookId`)")
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_entities_parentId` ON `entities` (`parentId`)")
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_entities_deletedAt` ON `entities` (`deletedAt`)")
            connection.executeDdl("ALTER TABLE `users` ADD COLUMN `canContributeStoryWorld` INTEGER NOT NULL DEFAULT 1")
            connection.executeDdl("ALTER TABLE `users` ADD COLUMN `canCurateStoryWorld` INTEGER NOT NULL DEFAULT 0")
            connection.executeDdl(
                "ALTER TABLE `admin_user_roster` ADD COLUMN `canContributeStoryWorld` INTEGER NOT NULL DEFAULT 1",
            )
            connection.executeDdl("ALTER TABLE `admin_user_roster` ADD COLUMN `canCurateStoryWorld` INTEGER NOT NULL DEFAULT 0")
            connection.executeDdl("ALTER TABLE `pending_operation` ADD COLUMN `mayHaveLanded` INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * v20 → v21: reading orders (#962).
 *
 * - The `reading_orders`, `reading_order_books` and `reading_order_follows` mirror tables. Their DDL is
 *   copied verbatim from the exported `schemas/…/21.json` `createSql` entries, so
 *   `runMigrationsAndValidate` sees the same schema a fresh v21 install has.
 * - `canMakeReadingOrders` on `users` and `admin_user_roster`, `DEFAULT 1`: additive, undoable work defaults
 *   on, matching the server's V96 column, so a mirrored row already agrees with the server. A revoked
 *   member's real value arrives with the next session refresh and roster sync.
 * - `books.releaseDate`, for Publication order. No cursor is rewound: existing rows fill in as each book
 *   next syncs, and ordering falls back to the publish year meanwhile. The three new domains have no
 *   cursor yet, so they pull from zero on their own.
 *
 * Non-destructive by construction — CREATE and ADD COLUMN only, per the policy in [ListenUpDatabase].
 */
internal val MIGRATION_20_21 =
    object : Migration(20, 21) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `reading_orders` (`id` TEXT NOT NULL, `seriesId` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_reading_orders_seriesId` ON `reading_orders` (`seriesId`)",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_reading_orders_deletedAt` ON `reading_orders` (`deletedAt`)",
            )
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `reading_order_books` (`readingOrderId` TEXT NOT NULL, " +
                    "`bookId` TEXT NOT NULL, `syncId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                    "`revision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                    "PRIMARY KEY(`readingOrderId`, `bookId`))",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_reading_order_books_bookId` ON `reading_order_books` (`bookId`)",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_reading_order_books_deletedAt` ON `reading_order_books` (`deletedAt`)",
            )
            connection.executeDdl(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_reading_order_books_syncId` ON `reading_order_books` (`syncId`)",
            )
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `reading_order_follows` (`id` TEXT NOT NULL, `seriesId` TEXT NOT NULL, " +
                    "`choice` TEXT NOT NULL, `readingOrderId` TEXT, `revision` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_reading_order_follows_seriesId` ON `reading_order_follows` (`seriesId`)",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_reading_order_follows_deletedAt` ON `reading_order_follows` (`deletedAt`)",
            )
            connection.executeDdl("ALTER TABLE `users` ADD COLUMN `canMakeReadingOrders` INTEGER NOT NULL DEFAULT 1")
            connection.executeDdl(
                "ALTER TABLE `admin_user_roster` ADD COLUMN `canMakeReadingOrders` INTEGER NOT NULL DEFAULT 1",
            )
            connection.executeDdl("ALTER TABLE `books` ADD COLUMN `releaseDate` TEXT")
        }
    }

/**
 * v21 → v22: Story World events (PR B).
 *
 * - The `world_events` and `world_event_mentions` mirror tables. Their DDL is copied verbatim from the
 *   exported `schemas/…/22.json` `createSql` entries, so `runMigrationsAndValidate` sees the same schema a
 *   fresh v22 install has.
 * - No cursor is rewound: the new domain has no cursor yet, so it pulls from zero on its own.
 *
 * Non-destructive by construction — CREATE only, per the policy in [ListenUpDatabase].
 */
internal val MIGRATION_21_22 =
    object : Migration(21, 22) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `world_events` (`id` TEXT NOT NULL, `homeSeriesId` TEXT, `homeBookId` TEXT, " +
                    "`bookId` TEXT, `positionMs` INTEGER, `type` TEXT NOT NULL, `text` TEXT NOT NULL, `detail` TEXT, " +
                    "`subjectEntityId` TEXT, `objectEntityId` TEXT, `createdBy` TEXT, " +
                    "`updatedBy` TEXT, `revision` INTEGER NOT NULL, `deletedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_world_events_homeSeriesId` ON `world_events` (`homeSeriesId`)")
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_world_events_homeBookId` ON `world_events` (`homeBookId`)")
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_world_events_bookId` ON `world_events` (`bookId`)")
            connection.executeDdl("CREATE INDEX IF NOT EXISTS `index_world_events_deletedAt` ON `world_events` (`deletedAt`)")
            connection.executeDdl(
                "CREATE TABLE IF NOT EXISTS `world_event_mentions` (`eventId` TEXT NOT NULL, `entityId` TEXT NOT NULL, " +
                    "PRIMARY KEY(`eventId`, `entityId`))",
            )
            connection.executeDdl(
                "CREATE INDEX IF NOT EXISTS `index_world_event_mentions_entityId` ON `world_event_mentions` (`entityId`)",
            )
        }
    }

/**
 * v22 → v23: `book_ratings.source` — where a listener's rating came from (Hardcover rating import).
 *
 * - Every existing row is a ListenUp rating (`DEFAULT 'LISTENUP'`).
 * - The `book_ratings` cursor is deleted, as [MIGRATION_15_16] did for its domain: a newer server may
 *   already have imported ratings from Hardcover, which this client stored without their source, and
 *   the cursored pull never re-sends an unchanged row. A missing cursor is `since = 0`.
 *
 * Non-destructive: one ADD COLUMN and a cursor reset.
 */
internal val MIGRATION_22_23 =
    object : Migration(22, 23) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.executeDdl("ALTER TABLE `book_ratings` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'LISTENUP'")
            connection.executeDdl("DELETE FROM `sync_cursor` WHERE `domainName` = 'book_ratings'")
        }
    }
