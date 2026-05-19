package com.calypsan.listenup.client.test.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import kotlinx.coroutines.Dispatchers

/**
 * Builds a fresh in-memory [ListenUpDatabase] for a single test. Uses [BundledSQLiteDriver]
 * to match production, so anything the schema/constraints/cascades enforce in the app also
 * holds in tests.
 *
 * Installs [FtsTestTableCallback] so the three `*_fts` virtual tables exist — they are not
 * Room entities and would otherwise be absent, breaking any FTS-backed test.
 *
 * Each call returns an isolated database — tests share no state.
 *
 * Scope: jvmTest only. Promoted to commonTest in W4 once cross-platform migration tests
 * need the same seam — see the W1 plan's checkpoint resolution on in-memory Room placement.
 *
 * Source: Room KMP testing guide — https://developer.android.com/kotlin/multiplatform/room.
 */
fun createInMemoryTestDatabase(): ListenUpDatabase =
    Room
        .inMemoryDatabaseBuilder<ListenUpDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(FtsTestTableCallback())
        .build()

/**
 * Recreates the FTS5 virtual tables that production builds via the platform
 * `FtsTableCallback`. The `*_fts` tables are not Room entities, so an in-memory
 * test database has none unless they are created explicitly here.
 */
private class FtsTestTableCallback : RoomDatabase.Callback() {
    // onOpen is safe here: each createInMemoryTestDatabase() call returns a fresh isolated
    // in-memory database, so onOpen fires exactly once per database lifetime — equivalent to
    // onCreate. Do not cargo-cult this pattern into persistent-database tests.
    override fun onOpen(connection: SQLiteConnection) {
        super.onOpen(connection)

        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
                bookId, title, subtitle, description, author, narrator, seriesName, genres,
                tokenize='porter'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS contributors_fts USING fts5(
                contributorId, name, description, tokenize='porter'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS series_fts USING fts5(
                seriesId, name, description, tokenize='porter'
            )
            """.trimIndent(),
        )
    }
}
