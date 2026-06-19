package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.DatabaseConfig as ExposedDatabaseConfig
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
 * Initializes the Hikari pool, runs schema migrations, and returns a [DatabaseHandle] that
 * exposes the connected Exposed `Database` alongside pool-control operations needed by the
 * restore orchestrator (close/reopen pool, vacuum). Idempotent for migrations — the runner tracks
 * applied versions in its `schema_migrations` table.
 */
object DatabaseFactory {
    fun init(config: DatabaseConfig): DatabaseHandle {
        val pool = buildPool(config)

        MigrationRunner(pool).migrate()

        val swappable = SwappableDataSource(pool)
        val dbFile = Path.of(config.jdbcUrl.removePrefix("jdbc:sqlite:"))
        return DatabaseHandle(
            database = Database.connect(swappable, databaseConfig = retryDatabaseConfig()),
            dataSource = swappable,
            poolFactory = { buildPool(config) },
            dbFilePath = dbFile,
        )
    }

    /**
     * A media request blocked on a saturated pool must fail fast, not hang for Hikari's 30s
     * default `connectionTimeout` — a 20s hang is exactly what lets an audio seek time out into
     * a "broken pipe" (#598).
     */
    private const val CONNECTION_TIMEOUT_MS = 10_000L

    /**
     * Logs the acquiring stack of any connection held longer than this — turns the residual
     * "what saturates the 8-connection pool during playback?" question (#598) into a
     * self-identifying log line the next time it happens.
     */
    private const val LEAK_DETECTION_MS = 20_000L

    /**
     * SQLite will wait up to this many milliseconds for a write-lock before returning
     * SQLITE_BUSY. Cures ordinary write-lock contention (e.g. a long-running writer
     * temporarily blocks a second writer). Does NOT cure SQLITE_BUSY_SNAPSHOT — that
     * error is returned immediately regardless of busy_timeout because the snapshot is
     * stale, not locked; only re-running the transaction fixes it (see [retryDatabaseConfig]).
     */
    private const val BUSY_TIMEOUT_MS = 5_000

    internal fun buildPool(config: DatabaseConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                maximumPoolSize = config.maxPoolSize
                connectionTimeout = CONNECTION_TIMEOUT_MS
                leakDetectionThreshold = LEAK_DETECTION_MS
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
                // Waits up to 5 s for a write-lock before surfacing SQLITE_BUSY to the
                // caller. Covers the common case of a briefly-held writer; does not cover
                // SQLITE_BUSY_SNAPSHOT (stale snapshot — needs transaction retry instead,
                // configured in retryDatabaseConfig below).
                addDataSourceProperty("busy_timeout", BUSY_TIMEOUT_MS.toString())
                validate()
            },
        )

    /**
     * Maximum number of times Exposed will re-run a transaction that throws a
     * [java.sql.SQLException] (including SQLITE_BUSY and SQLITE_BUSY_SNAPSHOT).
     * Exposed's default is 3 with zero delay, which is insufficient under 8 concurrent
     * writers. Ten attempts with jittered backoff reduces the failure probability to
     * near zero while staying well within the 5 s busy_timeout ceiling.
     */
    private const val DEFAULT_MAX_TX_ATTEMPTS = 10

    /**
     * Minimum milliseconds between transaction retry attempts (jitter lower bound).
     * Together with [DEFAULT_MAX_TX_RETRY_DELAY_MS] this produces a progressive jittered
     * backoff: first retry ≈ 10–55 ms, later retries up to ~500 ms.
     */
    private const val DEFAULT_MIN_TX_RETRY_DELAY_MS = 10L

    /**
     * Maximum milliseconds between transaction retry attempts (jitter upper bound).
     * Keeps the total wait time well within user-perceptible latency even in the worst case.
     */
    private const val DEFAULT_MAX_TX_RETRY_DELAY_MS = 500L

    /**
     * Exposed [ExposedDatabaseConfig] wired into every [Database.connect] call.
     *
     * Why retry at the Exposed layer, not the pool layer?
     * SQLITE_BUSY_SNAPSHOT is distinct from SQLITE_BUSY: it fires when a deferred
     * transaction's snapshot is already superseded by a committed write from another
     * connection. SQLite rejects the stale snapshot immediately — no amount of
     * busy_timeout waiting will resolve it. The only cure is to re-run the entire
     * transaction against the current snapshot. Exposed's [org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction]
     * already implements this: it catches any [java.sql.SQLException] and retries the
     * whole transaction block up to [ExposedDatabaseConfig.defaultMaxAttempts] times with
     * a jittered delay between attempts.
     *
     * Default vs override:
     * Exposed defaults to `defaultMaxAttempts = 3`, `defaultMinRetryDelay = 0`,
     * `defaultMaxRetryDelay = 0` — meaning three immediate back-to-back attempts with no
     * pause. Under a pool of 8 concurrent writers that is not enough: the probability of
     * all three retries also landing on a stale snapshot is non-trivial.
     */
    private fun retryDatabaseConfig(): ExposedDatabaseConfig =
        ExposedDatabaseConfig {
            defaultMaxAttempts = DEFAULT_MAX_TX_ATTEMPTS
            defaultMinRetryDelay = DEFAULT_MIN_TX_RETRY_DELAY_MS
            defaultMaxRetryDelay = DEFAULT_MAX_TX_RETRY_DELAY_MS
        }
}
