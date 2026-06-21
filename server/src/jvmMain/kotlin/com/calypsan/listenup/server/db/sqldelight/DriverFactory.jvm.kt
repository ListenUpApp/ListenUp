package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.sqlite.SQLiteConfig

/** Wait up to this many ms for a write-lock before SQLITE_BUSY (matches the native driver + tests). */
private const val BUSY_TIMEOUT_MS = 5_000

/**
 * JVM actual: opens the SQLite file at [dbPath] via [JdbcSqliteDriver] with the project-standard
 * PRAGMAs applied as JDBC connection PROPERTIES (via [SQLiteConfig.toProperties]) so they take
 * effect on EVERY connection. [JdbcSqliteDriver] opens a connection per operation, so a post-open
 * `driver.execute("PRAGMA …")` only configures a transient connection and is silently lost — the
 * bug this avoids.
 *
 * - `journal_mode=WAL` — concurrent readers alongside a single writer.
 * - `busy_timeout=5000` — wait up to 5 s for a write-lock before SQLITE_BUSY.
 * - foreign_keys is intentionally LEFT OFF on JVM: enabling it changes live-scan insert ordering and
 *   breaks `LibraryLessOnboardingE2ETest` (202→404). The native actual ([DriverFactory] on linuxX64)
 *   enforces FK; closing that JVM/native divergence (FK-clean scan + production FK enforcement) is a
 *   separate follow-up. SQLITE_BUSY_SNAPSHOT is handled by the retry in [suspendTransaction], not here.
 *
 * `Schema.create` is intentionally NOT called — [com.calypsan.listenup.server.db.MigrationRunner] owns
 * the schema history and has already run all migrations before this driver is opened.
 */
actual class DriverFactory {
    actual fun createDriver(dbPath: String): SqlDriver =
        JdbcSqliteDriver(
            "jdbc:sqlite:$dbPath",
            SQLiteConfig()
                .apply {
                    busyTimeout = BUSY_TIMEOUT_MS
                    setJournalMode(SQLiteConfig.JournalMode.WAL)
                }.toProperties(),
        )
}
