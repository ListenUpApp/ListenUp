package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.NO_VERSION_CHECK

/**
 * Linux/Native actual: opens the SQLite file at [dbPath] via [NativeSqliteDriver] backed by
 * SQLiter, with the project-standard PRAGMAs wired through [DatabaseConfiguration].
 *
 * PRAGMA strategy on native: SQLiter applies configuration at connection-open time via
 * [DatabaseConfiguration] and its [DatabaseConfiguration.Extended] sub-config — which maps
 * to the same underlying `sqlite3` calls as the JVM PRAGMA statements, but applied before
 * any application SQL runs. Specifically:
 * - [DatabaseConfiguration.journalMode] = [JournalMode.WAL] → `PRAGMA journal_mode=WAL`
 * - [DatabaseConfiguration.Extended.busyTimeout] = 5000 → `sqlite3_busy_timeout(db, 5000)`
 * - [DatabaseConfiguration.Extended.foreignKeyConstraints] = true → `PRAGMA foreign_keys=ON`
 *
 * [Schema.create] / [Schema.migrate] callbacks are left as no-ops — [MigrationRunner] owns the
 * schema history and has already applied all migrations before this driver is opened.
 *
 * [NO_VERSION_CHECK] (= -1) suppresses SQLDelight's own create/migrate version checks entirely;
 * we never want SQLDelight to call create/migrate on our behalf.
 */
actual class DriverFactory {
    actual fun createDriver(dbPath: String): SqlDriver {
        return NativeSqliteDriver(
            configuration = DatabaseConfiguration(
                name = dbPath,
                version = NO_VERSION_CHECK,
                journalMode = JournalMode.WAL,
                extendedConfig = DatabaseConfiguration.Extended(
                    foreignKeyConstraints = true,
                    busyTimeout = 5_000,
                ),
                create = { /* MigrationRunner owns schema creation */ },
                upgrade = { _, _, _ -> /* MigrationRunner owns migrations */ },
            ),
            maxReaderConnections = 1,
        )
    }
}
