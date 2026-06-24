package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific factory that opens a SQLite [SqlDriver] at [dbPath] with the project's
 * standard PRAGMAs applied (WAL journal mode, 5 s busy_timeout, foreign-key enforcement).
 *
 * The factory does NOT call [Schema.create] — [MigrationRunner] owns the schema and has
 * already applied migrations before the SQLDelight driver is first used.
 */
expect class DriverFactory() {
    fun createDriver(dbPath: String): SqlDriver
}
