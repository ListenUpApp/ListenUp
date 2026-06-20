package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * JVM actual: opens the SQLite file at [dbPath] via the JDBC-backed [JdbcSqliteDriver] and
 * applies the project-standard PRAGMAs.
 *
 * PRAGMA notes:
 * - `journal_mode=WAL` — enables concurrent readers alongside a single writer; matches the
 *   Hikari/Exposed pool configuration.
 * - `busy_timeout=5000` — waits up to 5 s for a write-lock before returning SQLITE_BUSY
 *   (matches [DatabaseFactory.BUSY_TIMEOUT_MS]).
 * - `foreign_keys=ON` — SQLite disables FK enforcement per-connection by default; enabling
 *   it here mirrors the Hikari `addDataSourceProperty("foreign_keys", "true")` setting.
 *
 * [Schema.create] is intentionally NOT called — [MigrationRunner] owns the schema history
 * and has already run all migrations before this driver is opened.
 */
actual class DriverFactory {
    actual fun createDriver(dbPath: String): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        // Apply PRAGMAs immediately after opening — before any query runs.
        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
        driver.execute(null, "PRAGMA busy_timeout=5000;", 0)
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        return driver
    }
}
