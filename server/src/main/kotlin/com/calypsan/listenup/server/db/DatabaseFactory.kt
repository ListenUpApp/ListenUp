package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Path

/**
 * Database connection settings. JDBC URL is the only required input;
 * for SQLite, username/password are unused (kept for future-proofing).
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String = "",
    val password: String = "",
    val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
) {
    companion object {
        private const val DEFAULT_MAX_POOL_SIZE = 8
    }
}

/**
 * Initializes the Hikari pool, runs Flyway migrations, and returns a [DatabaseHandle] that
 * exposes the connected Exposed `Database` alongside pool-control operations needed by the
 * restore orchestrator (close/reopen pool, vacuum). Idempotent for migrations — Flyway tracks
 * applied versions in its `flyway_schema_history` table.
 */
object DatabaseFactory {
    fun init(config: DatabaseConfig): DatabaseHandle {
        val pool = buildPool(config)

        Flyway
            .configure()
            .dataSource(pool)
            .locations("classpath:db/migration")
            // Load migrations by name from the committed index rather than scanning the classpath:
            // the only thing that works under native-image (no walkable classpath), equivalent to
            // scanning on the JVM. Single path keeps JVM and native behaviour identical. (#647)
            .resourceProvider(BundledMigrationResourceProvider())
            .load()
            .migrate()

        val swappable = SwappableDataSource(pool)
        val dbFile = Path.of(config.jdbcUrl.removePrefix("jdbc:sqlite:"))
        return DatabaseHandle(
            database = Database.connect(swappable),
            dataSource = swappable,
            poolFactory = { buildPool(config) },
            dbFilePath = dbFile,
        )
    }

    private fun buildPool(config: DatabaseConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_SERIALIZABLE"
                // SQLite has FK enforcement off per-connection by default. The property
                // key must be the pragma name (`foreign_keys`), not the SQLiteConfig
                // setter name (`enforceForeignKeys`) — sqlite-jdbc's `SQLiteConfig` reads
                // pragma-keyed properties at connection init, which runs before Hikari
                // calls `setAutoCommit(false)`. `connectionInitSql` is the wrong tool here
                // because SQLite ignores `PRAGMA foreign_keys` inside an active transaction,
                // and `isAutoCommit = false` opens one before that SQL would run.
                addDataSourceProperty("foreign_keys", "true")
                // WAL mode allows concurrent readers alongside a writer, eliminating the
                // SQLITE_BUSY contention that arises when :server:test runs multiple
                // testApplication instances in parallel. The pragma key is accepted by
                // sqlite-jdbc's SQLiteConfig and applied at connection-open time.
                addDataSourceProperty("journal_mode", "wal")
                validate()
            },
        )
}
