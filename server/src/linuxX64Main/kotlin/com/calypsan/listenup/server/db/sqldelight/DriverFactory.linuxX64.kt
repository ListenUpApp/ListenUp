package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.SynchronousFlag
import kotlinx.io.files.Path

/**
 * Linux/Native actual: opens the SQLite file at [dbPath] via [NativeSqliteDriver] backed by
 * SQLiter, with the project-standard PRAGMAs wired through [DatabaseConfiguration].
 *
 * PRAGMA strategy on native: SQLiter applies configuration at connection-open time via
 * [DatabaseConfiguration] and its [DatabaseConfiguration.Extended] sub-config — which maps
 * to the same underlying `sqlite3` calls as the JVM PRAGMA statements, but applied before
 * any application SQL runs. Specifically:
 * - [DatabaseConfiguration.journalMode] = [JournalMode.WAL] → `PRAGMA journal_mode=WAL`
 * - [DatabaseConfiguration.Extended.synchronousFlag] = [SynchronousFlag.NORMAL] → `PRAGMA synchronous=NORMAL`
 *   — the SQLite-recommended WAL companion: `fsync` only at a checkpoint, not per commit. Commits stay
 *   durable across an application crash and the DB is never corrupted by an OS crash / power loss (only
 *   the last in-flight transaction(s) may be lost, which the sync engine reconciles on reconnect).
 *   Bulk library persistence + ABS-import progress commit one small transaction per book/row, so the
 *   default `synchronous=FULL`'s per-commit `fsync` dominated those flows; NORMAL removes it. Matches
 *   the JVM driver so both engines write with identical durability semantics.
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
        // SQLiter's [DatabaseConfiguration.name] must be a bare filename — it rejects a path with a
        // separator — and the directory goes in [DatabaseConfiguration.Extended.basePath]. (The JVM
        // JDBC driver and our migration cinterop both take a full path, so only this driver needs the
        // split.) [basePath] is null for a bare filename, leaving SQLiter's default directory.
        val path = Path(dbPath)
        return NativeSqliteDriver(
            configuration =
                DatabaseConfiguration(
                    name = path.name,
                    version = NO_VERSION_CHECK,
                    journalMode = JournalMode.WAL,
                    extendedConfig =
                        DatabaseConfiguration.Extended(
                            foreignKeyConstraints = true,
                            busyTimeout = 5_000,
                            synchronousFlag = SynchronousFlag.NORMAL,
                            basePath = path.parent?.toString(),
                        ),
                    create = {
                        // MigrationRunner owns schema creation
                    },
                    upgrade = { _, _, _ ->
                        // MigrationRunner owns migrations
                    },
                ),
            maxReaderConnections = 1,
        )
    }
}
