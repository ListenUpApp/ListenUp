package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Owns the repos' restore-swappable [SwappableSqlDriver], the non-pooled migration/VACUUM
 * [SwappableDataSource], and the on-disk db file, so the restore orchestrator can close every handle,
 * swap the file in place, reopen, and migrate forward while keeping the [SwappableSqlDriver] (and thus
 * every repository bound to it) identity-stable.
 */
class DatabaseHandle(
    val sqlDriver: SwappableSqlDriver,
    private val dataSource: SwappableDataSource,
    private val dataSourceFactory: () -> DataSource,
    val dbFilePath: Path,
) {
    @Volatile
    private var closed = false

    /**
     * Asks SQLite for a transactionally-consistent standalone copy at [target] (WAL-safe). SQLite
     * requires `VACUUM INTO` run outside a transaction, so we use a raw auto-commit JDBC connection.
     */
    fun vacuumInto(target: Path) {
        val escapedPath = target.toAbsolutePath().toString().replace("'", "''")
        dataSource.connection.use { conn ->
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = true
            try {
                conn.createStatement().use { stmt -> stmt.execute("VACUUM INTO '$escapedPath'") }
            } finally {
                conn.autoCommit = wasAutoCommit
            }
        }
    }

    /**
     * Hard-closes the repos' SQLDelight driver and the migration data source, releasing every SQLite
     * file handle before the db file is swapped. MUST be paired with [reopenPool] on every control-flow
     * path or the app is left with closed connections.
     */
    fun closePool() {
        sqlDriver.closeUnderlying()
        dataSource.closeCurrent()
    }

    /**
     * Rebuilds the SQLDelight driver and the migration data source on the (possibly just-swapped) db
     * file, making both live again. The [SwappableSqlDriver] identity is preserved so repositories that
     * captured it keep working after the restore.
     */
    fun reopenPool() {
        dataSource.install(dataSourceFactory())
        sqlDriver.installUnderlying(DriverFactory().createDriver(dbFilePath.toAbsolutePath().toString()))
    }

    /** Runs migrations forward against the current (possibly just-swapped) db file. Returns the post-migration version. */
    fun migrate(): String? = MigrationRunner(dataSource).migrate()

    /** The applied schema version, or null on a fresh/empty db. */
    fun currentSchemaVersion(): String? = MigrationRunner(dataSource).currentSchemaVersion()

    /**
     * Terminal shutdown — closes the SQLDelight driver and the migration data source; nothing reopens.
     * Idempotent: repeat calls are tolerated.
     */
    fun close() {
        closed = true
        sqlDriver.close()
        dataSource.close()
    }

    /** True once [close] has run. Test-observability for graceful shutdown. */
    fun isPoolClosed(): Boolean = closed

    /** Test-only: the live DataSource, for schema-dump / raw-seed assertions. */
    internal fun dataSourceForTest(): DataSource = dataSource
}
