package com.calypsan.listenup.server.testing

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import java.nio.file.Files
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * A migrated test SQLite database exposed as a SQLDelight [ListenUpDatabase] together with its
 * backing [driver] — handed back from [migratedTestDatabase] for the factory-style tests that
 * build services directly (auth, session) rather than through the block-scoped [withSqlDatabase].
 *
 * Most callers use only [db]; [driver] is exposed for the rare test that needs raw SQL to plant a
 * row that bypasses a service-layer guard (e.g. a legacy over-long column value).
 */
data class TestDatabase(
    val db: ListenUpDatabase,
    val driver: SqlDriver,
)

/**
 * Creates a fresh temp-file SQLite database, runs the Flyway migrations via [DatabaseFactory.init]
 * (so the SQLDelight driver never calls `Schema.create`), and returns it as a SQLDelight
 * [ListenUpDatabase].
 *
 * Foreign-key enforcement + busy-timeout + WAL are set as JDBC connection *properties* (not a
 * one-time `PRAGMA`, which [JdbcSqliteDriver] loses because it opens a connection per operation) —
 * the same configuration [withSqlDatabase] uses. The temp file is deleted on JVM exit.
 */
fun migratedTestDatabase(): TestDatabase {
    val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
    val path = tmp.absolutePath
    DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$path"))
    val driver =
        JdbcSqliteDriver(
            "jdbc:sqlite:$path",
            SQLiteConfig()
                .apply {
                    enforceForeignKeys(true)
                    busyTimeout = 5000
                    setJournalMode(SQLiteConfig.JournalMode.WAL)
                }.toProperties(),
        )
    return TestDatabase(db = ListenUpDatabase(driver), driver = driver)
}

/**
 * A non-pooled file-backed [SQLiteDataSource] over [jdbcUrl] for migration/schema tests that drive
 * [com.calypsan.listenup.server.db.MigrationRunner] directly (the Hikari-free replacement for the
 * pooled data source those tests used to build). FK enforcement + busy_timeout + WAL as connection
 * properties. Non-pooled: each `connection.use {}` opens+closes its own connection, so there is
 * nothing to close on the data source itself; the temp file is the caller's to delete.
 */
fun fileBackedTestDataSource(jdbcUrl: String): SQLiteDataSource =
    SQLiteDataSource(
        SQLiteConfig().apply {
            enforceForeignKeys(true)
            busyTimeout = 5000
            setJournalMode(SQLiteConfig.JournalMode.WAL)
        },
    ).apply { url = jdbcUrl }
