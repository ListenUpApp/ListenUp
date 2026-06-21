package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v32 → v33 — drop the unused cover-color columns.
 *
 * -- DESTRUCTIVE: removes `dominantColor`, `darkMutedColor`, and `vibrantColor`
 * from `books`. These were populated by the now-removed client-side cover-color
 * extraction feature; nothing reads them anymore — the edit and now-playing
 * screens render Material You theme colors instead. The values are intentionally
 * NOT carried across.
 *
 * `books` is rebuilt via the create-new → copy → drop → rename pattern because
 * SQLite (through Room's bundled driver) cannot drop columns. The TEXT `id`
 * primary key and the `index_books_libraryId` / `index_books_folderId` indices
 * are preserved; `books` has no foreign keys of its own, and the child-table FKs
 * that reference it survive the rename since they resolve by table name and the
 * `id` values carry across unchanged.
 */
internal val MIGRATION_32_33: Migration =
    object : Migration(32, 33) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE `books_new` (
                    `id` TEXT NOT NULL,
                    `libraryId` TEXT NOT NULL,
                    `folderId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `sortTitle` TEXT,
                    `subtitle` TEXT,
                    `coverHash` TEXT,
                    `coverBlurHash` TEXT,
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
                    `hasScanWarning` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO `books_new` (
                    `id`, `libraryId`, `folderId`, `title`, `sortTitle`, `subtitle`,
                    `coverHash`, `coverBlurHash`, `totalDuration`, `description`,
                    `publishYear`, `publisher`, `language`, `isbn`, `asin`, `abridged`,
                    `revision`, `deletedAt`, `hasScanWarning`, `createdAt`, `updatedAt`
                )
                SELECT
                    `id`, `libraryId`, `folderId`, `title`, `sortTitle`, `subtitle`,
                    `coverHash`, `coverBlurHash`, `totalDuration`, `description`,
                    `publishYear`, `publisher`, `language`, `isbn`, `asin`, `abridged`,
                    `revision`, `deletedAt`, `hasScanWarning`, `createdAt`, `updatedAt`
                FROM `books`
                """.trimIndent(),
            )
            connection.execSQL("DROP TABLE `books`")
            connection.execSQL("ALTER TABLE `books_new` RENAME TO `books`")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_books_libraryId` ON `books` (`libraryId`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_books_folderId` ON `books` (`folderId`)")
        }
    }
