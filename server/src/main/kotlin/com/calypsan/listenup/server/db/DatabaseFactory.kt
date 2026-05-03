package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

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
 * Initializes the Hikari pool, runs Flyway migrations, and returns a
 * connected Exposed `Database`. Idempotent for migrations — Flyway tracks
 * applied versions in its `flyway_schema_history` table.
 */
object DatabaseFactory {
    fun init(config: DatabaseConfig): Database {
        val hikari =
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
                    validate()
                },
            )

        Flyway
            .configure()
            .dataSource(hikari)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        return Database.connect(hikari)
    }
}
