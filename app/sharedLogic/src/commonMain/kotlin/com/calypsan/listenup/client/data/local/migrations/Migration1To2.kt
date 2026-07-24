package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v1 → v2 — the first post-squash migration.
 *
 * SERVER-SYNC-04: the server's junction wire ids (`collection_books`/`book_tags`/`book_moods`)
 * stopped encoding the natural pair (`"$a:$b"`) and became opaque per-row values. The client must
 * store that wire id instead of re-deriving it at read time, so each junction table gains a
 * `syncId` column (unique, matched against `SyncEvent.Deleted.id`; never parsed).
 *
 * -- DESTRUCTIVE (junction mirror rows only): a pre-migration row's real `syncId` is unknowable —
 * it lived only in the now-superseded composite-id scheme. Rather than fabricate one, this
 * migration DELETES every `collection_books`/`book_tags`/`book_moods` row; the next digest
 * reconcile re-pulls them from the server with real `syncId` values. This is intentional healing,
 * not data loss: the server is the source of truth for these junctions, and the deleted rows are
 * pure mirrors of server state (no unsynced local edits reference them directly — an in-flight
 * outbox op for a junction write carries its own `bookId`/`collectionId` pair, not a row id).
 *
 * `shelf_books` is NOT touched — its `id` column already stored the wire id (Room v24), and the
 * VALUE just became opaque server-side; no local schema change is needed for it.
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DELETE FROM collection_books")
            connection.execSQL("DELETE FROM book_tags")
            connection.execSQL("DELETE FROM book_moods")

            connection.execSQL("ALTER TABLE collection_books ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
            connection.execSQL("ALTER TABLE book_tags ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
            connection.execSQL("ALTER TABLE book_moods ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")

            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_collection_books_syncId` ON `collection_books` (`syncId`)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_tags_syncId` ON `book_tags` (`syncId`)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_moods_syncId` ON `book_moods` (`syncId`)",
            )
        }
    }
