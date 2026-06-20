package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v14 → v15 — the Books-A sync substrate landing.
 *
 * Drops the legacy `Syncable` columns (`syncState`, `lastModified`,
 * `serverVersion`) from `books`, `chapters`, `series`, and `contributors`,
 * since the Books-A sync engine no longer tracks per-entity sync state on the
 * client. In their place, `books` gains the new substrate columns `revision`
 * (monotonic server revision) and `deletedAt` (epoch-ms tombstone).
 *
 * Two columns are renamed to match the new wire contract:
 * - `books.coverUrl` → `books.coverHash` — the value is NOT carried across.
 *   The old column held a URL path; the new column holds a content hash that
 *   the Books-A sync handler repopulates from the wire. It starts null.
 * - `series.coverImagePath` → `series.coverPath` — value carried as-is.
 *
 * Each table is rebuilt via the create-new → copy → drop → rename pattern
 * because SQLite cannot drop columns in older versions and Room's bundled
 * driver targets the lowest common denominator. Primary keys (TEXT `id`) are
 * preserved; the junction tables (`book_contributors`, `book_series`, etc.)
 * declare `ON DELETE CASCADE` FKs referencing these `id` columns, and those
 * constraints survive untouched since the primary-key values and column name
 * carry across. The non-`syncState` index `index_chapters_bookId` is
 * recreated on the rebuilt `chapters` table.
 */
internal val MIGRATION_14_15: Migration =
    object : Migration(14, 15) {
        override fun migrate(connection: SQLiteConnection) {
            connection.migrateBooks()
            connection.migrateChapters()
            connection.migrateSeries()
            connection.migrateContributors()
        }
    }

/**
 * Rebuilds `books`: drops the `Syncable` columns, renames `coverUrl` away
 * (the value is intentionally NOT carried — `coverHash` starts null), and adds
 * the Books-A substrate columns `revision` and `deletedAt`.
 */
private fun SQLiteConnection.migrateBooks() {
    execSQL(
        """
        CREATE TABLE `books_new` (
            `id` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `sortTitle` TEXT,
            `subtitle` TEXT,
            `coverHash` TEXT,
            `coverBlurHash` TEXT,
            `dominantColor` INTEGER,
            `darkMutedColor` INTEGER,
            `vibrantColor` INTEGER,
            `totalDuration` INTEGER NOT NULL,
            `description` TEXT,
            `publishYear` INTEGER,
            `publisher` TEXT,
            `language` TEXT,
            `isbn` TEXT,
            `asin` TEXT,
            `abridged` INTEGER NOT NULL,
            `revision` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO `books_new` (
            `id`, `title`, `sortTitle`, `subtitle`, `coverHash`, `coverBlurHash`,
            `dominantColor`, `darkMutedColor`, `vibrantColor`, `totalDuration`,
            `description`, `publishYear`, `publisher`, `language`, `isbn`, `asin`,
            `abridged`, `revision`, `deletedAt`, `createdAt`, `updatedAt`
        )
        SELECT
            `id`, `title`, `sortTitle`, `subtitle`, NULL, `coverBlurHash`,
            `dominantColor`, `darkMutedColor`, `vibrantColor`, `totalDuration`,
            `description`, `publishYear`, `publisher`, `language`, `isbn`, `asin`,
            `abridged`, 0, NULL, `createdAt`, `updatedAt`
        FROM `books`
        """.trimIndent(),
    )
    execSQL("DROP TABLE `books`")
    execSQL("ALTER TABLE `books_new` RENAME TO `books`")
}

/**
 * Rebuilds `chapters`: drops the `Syncable` columns and recreates the
 * `index_chapters_bookId` index that survives into v15.
 */
private fun SQLiteConnection.migrateChapters() {
    execSQL(
        """
        CREATE TABLE `chapters_new` (
            `id` TEXT NOT NULL,
            `bookId` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `duration` INTEGER NOT NULL,
            `startTime` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO `chapters_new` (`id`, `bookId`, `title`, `duration`, `startTime`)
        SELECT `id`, `bookId`, `title`, `duration`, `startTime`
        FROM `chapters`
        """.trimIndent(),
    )
    execSQL("DROP TABLE `chapters`")
    execSQL("ALTER TABLE `chapters_new` RENAME TO `chapters`")
    execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId` ON `chapters` (`bookId`)")
}

/**
 * Rebuilds `series`: drops the `Syncable` columns and renames
 * `coverImagePath` to `coverPath`, carrying the value across.
 */
private fun SQLiteConnection.migrateSeries() {
    execSQL(
        """
        CREATE TABLE `series_new` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `description` TEXT,
            `asin` TEXT,
            `coverPath` TEXT,
            `coverBlurHash` TEXT,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO `series_new` (
            `id`, `name`, `description`, `asin`, `coverPath`, `coverBlurHash`,
            `createdAt`, `updatedAt`
        )
        SELECT
            `id`, `name`, `description`, `asin`, `coverImagePath`, `coverBlurHash`,
            `createdAt`, `updatedAt`
        FROM `series`
        """.trimIndent(),
    )
    execSQL("DROP TABLE `series`")
    execSQL("ALTER TABLE `series_new` RENAME TO `series`")
}

/** Rebuilds `contributors`: drops the `Syncable` columns. */
private fun SQLiteConnection.migrateContributors() {
    execSQL(
        """
        CREATE TABLE `contributors_new` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `sortName` TEXT,
            `asin` TEXT,
            `description` TEXT,
            `imagePath` TEXT,
            `imageBlurHash` TEXT,
            `website` TEXT,
            `birthDate` TEXT,
            `deathDate` TEXT,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO `contributors_new` (
            `id`, `name`, `sortName`, `asin`, `description`, `imagePath`,
            `imageBlurHash`, `website`, `birthDate`, `deathDate`, `createdAt`, `updatedAt`
        )
        SELECT
            `id`, `name`, `sortName`, `asin`, `description`, `imagePath`,
            `imageBlurHash`, `website`, `birthDate`, `deathDate`, `createdAt`, `updatedAt`
        FROM `contributors`
        """.trimIndent(),
    )
    execSQL("DROP TABLE `contributors`")
    execSQL("ALTER TABLE `contributors_new` RENAME TO `contributors`")
}
