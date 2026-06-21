package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Path

/**
 * Database connection settings. JDBC URL is the only required input; for SQLite, username/password
 * are unused (kept for future-proofing).
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String = "",
    val password: String = "",
)

/**
 * Runs schema migrations and returns a [DatabaseHandle] exposing the repos' restore-swappable
 * [SwappableSqlDriver] and a non-pooled migration/VACUUM [SwappableDataSource]. No Exposed, no Hikari:
 * the SQLDelight driver is the single connection authority; migrations + `VACUUM INTO` use a non-pooled
 * sqlite-jdbc [SQLiteDataSource] (each connection opened+closed per use, so nothing to pool or leak).
 * Idempotent migrations — the runner tracks applied versions in its `schema_migrations` table.
 */
object DatabaseFactory {
    /** SQLite waits up to this many ms for a write-lock before SQLITE_BUSY (matches the SQLDelight driver). */
    private const val BUSY_TIMEOUT_MS = 5_000

    fun init(config: DatabaseConfig): DatabaseHandle {
        val dbFile = Path.of(config.jdbcUrl.removePrefix("jdbc:sqlite:"))
        val dataSource = SwappableDataSource(buildDataSource(config))

        MigrationRunner(dataSource).migrate()

        val sqlDriver = SwappableSqlDriver(DriverFactory().createDriver(dbFile.toString()))
        return DatabaseHandle(
            sqlDriver = sqlDriver,
            dataSource = dataSource,
            dataSourceFactory = { buildDataSource(config) },
            dbFilePath = dbFile,
        )
    }

    /**
     * A non-pooled [SQLiteDataSource] with WAL + busy_timeout as connection properties (foreign_keys is
     * left off on JVM — see [DriverFactory] jvm actual). Used only for migrations + `VACUUM INTO`
     * (sequential, rare) — no connection pool.
     */
    internal fun buildDataSource(config: DatabaseConfig): SQLiteDataSource =
        SQLiteDataSource(
            SQLiteConfig().apply {
                busyTimeout = BUSY_TIMEOUT_MS
                setJournalMode(SQLiteConfig.JournalMode.WAL)
            },
        ).apply { url = config.jdbcUrl }
}
