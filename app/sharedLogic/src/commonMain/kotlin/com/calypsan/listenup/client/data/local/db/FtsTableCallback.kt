package com.calypsan.listenup.client.data.local.db

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room callback that ensures the FTS5 search tables exist on every platform.
 *
 * The `books_fts` / `contributors_fts` / `series_fts` virtual tables cannot be declared as
 * Room entities (see [BookFtsEntry] et al. for why FTS5 isn't expressible as `@Entity` on
 * Room 2.8), so they are created here via raw `CREATE VIRTUAL TABLE`. `onOpen` (not
 * `onCreate`) guarantees the tables exist on fresh installs *and* on databases that predate
 * this callback — `IF NOT EXISTS` makes it idempotent.
 *
 * Lives in `commonMain` and is added to the Room builder by every platform's
 * `platformDatabaseModule`. Forgetting to add it on a platform silently breaks all
 * full-text search there: `SearchDao`'s `MATCH` queries and the FTS rebuild fail with
 * `no such table: books_fts`.
 */
internal class FtsTableCallback : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        super.onOpen(connection)

        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
                bookId,
                title,
                subtitle,
                description,
                author,
                narrator,
                seriesName,
                genres,
                tokenize='porter'
            )
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS contributors_fts USING fts5(
                contributorId,
                name,
                sortName,
                aliases,
                description,
                tokenize='porter'
            )
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS series_fts USING fts5(
                seriesId,
                name,
                description,
                tokenize='porter'
            )
            """.trimIndent(),
        )
    }
}
