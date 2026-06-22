package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v34 → v35 — add the `book_documents` table.
 *
 * Backs the supplementary-documents feature: per-book PDF/ebook rows synced from the server's
 * `book_documents` table. The CREATE TABLE mirrors the audio-files table's shape (the
 * authoritative form is the exported `35.json` schema): composite PK `(bookId, index)`, a
 * cascade FK to `books`, and an index on the FK child column `bookId` (Room requires it). The
 * `hash` column carries the document's SHA-256 (serve-route ETag + cache key) where audio files
 * carry `codec`/`duration`. A fresh install creates this table directly from the entity; only
 * upgrades from v34 run this migration.
 */
internal val MIGRATION_34_35: Migration =
    object : Migration(34, 35) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `book_documents` " +
                    "(`bookId` TEXT NOT NULL, `index` INTEGER NOT NULL, `id` TEXT NOT NULL, " +
                    "`filename` TEXT NOT NULL, `format` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                    "`hash` TEXT NOT NULL, PRIMARY KEY(`bookId`, `index`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_book_documents_bookId` ON `book_documents` (`bookId`)",
            )
        }
    }
