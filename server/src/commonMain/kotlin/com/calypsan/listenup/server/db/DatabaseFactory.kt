package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.db.sqldelight.DriverFactory

/**
 * Database connection settings. The JDBC URL is the only required input; for SQLite, username/password
 * are unused (kept for future-proofing).
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String = "",
    val password: String = "",
)

/**
 * Runs schema migrations, then returns a [DatabaseHandle] exposing the repos' restore-swappable
 * [SwappableSqlDriver]. Migrations + `VACUUM INTO` open a short-lived admin connection per call (no
 * pool, no DataSource). Idempotent — the runner tracks applied versions in its `schema_migrations` table.
 */
object DatabaseFactory {
    fun init(config: DatabaseConfig): DatabaseHandle {
        val dbPath = config.jdbcUrl.removePrefix("jdbc:sqlite:")
        MigrationRunner(dbPath).migrate()
        val sqlDriver = SwappableSqlDriver(DriverFactory().createDriver(dbPath))
        return DatabaseHandle(sqlDriver = sqlDriver, dbPath = dbPath)
    }
}
