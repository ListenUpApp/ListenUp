package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Path

/**
 * Owns the live [Database] plus the underlying [SwappableDataSource] and the on-disk db file,
 * so the restore orchestrator can close the pool, swap the file in place, reopen the pool,
 * and migrate forward while keeping the [Database] object identity stable (repositories
 * captured it at construction).
 */
class DatabaseHandle(
    val database: Database,
    private val dataSource: SwappableDataSource,
    private val poolFactory: () -> HikariDataSource,
    val dbFilePath: Path,
) {
    /**
     * Asks SQLite for a transactionally-consistent standalone copy at [target] (WAL-safe).
     *
     * SQLite requires `VACUUM INTO` to run outside of a transaction, so we use a raw JDBC
     * connection with auto-commit enabled rather than Exposed's `transaction { }` wrapper.
     */
    fun vacuumInto(target: Path) {
        val escapedPath = target.toAbsolutePath().toString().replace("'", "''")
        dataSource.connection.use { conn ->
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = true
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute("VACUUM INTO '$escapedPath'")
                }
            } finally {
                conn.autoCommit = wasAutoCommit
            }
        }
    }

    /**
     * Hard-closes the live Hikari pool, deterministically releasing every SQLite file
     * handle before the db file is swapped. MUST be paired with [reopenPool] on every
     * control-flow path or the app is left with a closed pool.
     *
     * Conversely, [reopenPool] must only be called after [closePool] — installing a new
     * pool over a still-open one orphans the old pool's connections (file-handle leak).
     */
    fun closePool() = dataSource.closeCurrent()

    /**
     * Builds a fresh pool on the (possibly just-swapped) db file and makes it live.
     *
     * The fresh pool is built from the original [poolFactory] (i.e. the original
     * jdbcUrl/path); this is correct only because restore swaps the replacement db file
     * into that same path in place.
     */
    fun reopenPool() = dataSource.install(poolFactory())

    /** Runs Flyway forward against the current (possibly just-swapped) db file. Returns the post-migration version. */
    fun migrate(): String? =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
            .let { currentSchemaVersion() }

    /** The applied Flyway version, or null on a fresh/empty db. */
    fun currentSchemaVersion(): String? =
        Flyway
            .configure()
            .dataSource(
                dataSource,
            ).locations("classpath:db/migration")
            .load()
            .info()
            .current()
            ?.version
            ?.version

    /**
     * Terminal shutdown of the connection pool — releases every Hikari thread and SQLite
     * file handle. Unlike [closePool] (the restore-flow close that expects a [reopenPool]),
     * nothing reopens after this. Idempotent: HikariCP's close tolerates repeat calls.
     */
    fun close() = dataSource.close()

    /** True once the live pool is closed. Test-observability for graceful shutdown. */
    fun isPoolClosed(): Boolean = dataSource.current().isClosed
}
