package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Path

/**
 * Owns the live [Database] plus the underlying Hikari pool and the on-disk db file, so the
 * restore orchestrator can freeze the pool, swap the file in place, and migrate forward while
 * keeping the [Database] object identity stable (repositories captured it at construction).
 */
class DatabaseHandle(
    val database: Database,
    private val dataSource: HikariDataSource,
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

    fun suspendPool() = dataSource.hikariPoolMXBean.suspendPool()

    fun resumePool() = dataSource.hikariPoolMXBean.resumePool()

    fun evictConnections() = dataSource.hikariPoolMXBean.softEvictConnections()

    /**
     * Blocks until the pool reports zero physical connections, or [timeoutMs] elapses.
     * Returns true if fully drained, false on timeout.
     *
     * After [suspendPool] (no new acquisitions), the db file must not be swapped until every
     * SQLite handle is released — otherwise the swap/migrate races a lingering connection and
     * hits `SQLITE_BUSY`. `softEvictConnections` only *requests* eviction; this polls
     * `totalConnections` (re-nudging eviction each pass) until the pool is physically drained.
     * Bounded so a stuck connection can't freeze restore forever — on timeout the caller
     * proceeds, and any genuine lock surfaces as a normal rollback rather than a hang.
     */
    fun awaitPoolDrained(timeoutMs: Long = DRAIN_TIMEOUT_MS): Boolean {
        val pool = dataSource.hikariPoolMXBean
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pool.totalConnections > 0) {
            if (System.currentTimeMillis() >= deadline) return false
            pool.softEvictConnections()
            Thread.sleep(DRAIN_POLL_MS)
        }
        return true
    }

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

    fun close() = dataSource.close()

    private companion object {
        const val DRAIN_TIMEOUT_MS = 5_000L
        const val DRAIN_POLL_MS = 20L
    }
}
