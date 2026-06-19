package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files

/**
 * Stress-tests SQLite concurrency under WAL + a Hikari pool to reproduce
 * SQLITE_BUSY_SNAPSHOT and verify the fix.
 *
 * Why a file-backed DB, not :memory:?  Each Hikari pool connection opens its own
 * SQLite handle; in :memory: mode every handle gets a completely private, independent
 * database — there is no shared WAL, so there is nothing to contend on.  File-backed
 * WAL is the only mode where multiple pool connections share a single page-cache and
 * therefore race to commit, which is what triggers SQLITE_BUSY_SNAPSHOT.
 *
 * The BUSY_SNAPSHOT error occurs when a deferred transaction reads a snapshot that has
 * already been superseded by a committed write from another connection.  busy_timeout
 * does NOT cure it (it only waits for a write lock; a snapshot mismatch is rejected
 * immediately regardless of the timeout).  The only cure is to re-run the whole
 * transaction against the current snapshot — i.e., retry.
 *
 * Exposed 1.3.0's [suspendTransaction] already retries any [java.sql.SQLException] up to
 * [org.jetbrains.exposed.v1.core.DatabaseConfig.defaultMaxAttempts] times.  The fix in
 * [DatabaseFactory] wires the [org.jetbrains.exposed.v1.core.DatabaseConfig] with a
 * [org.jetbrains.exposed.v1.jdbc.Database.connect] call that sets `defaultMaxAttempts = 10`,
 * `defaultMinRetryDelay = 10`, `defaultMaxRetryDelay = 500`, and adds `busy_timeout = 5000`
 * so ordinary write-lock contention also resolves without surfacing to callers.
 */
class TransactionRetryConcurrencyTest :
    FunSpec({

        // Drives DatabaseFactory.init (WAL pool size 8 + retry config) against 8 concurrent
        // coroutines each doing 50 read-then-write transactions. Before the fix this failed
        // with SQLITE_BUSY because Exposed's default 3 immediate retries were not enough
        // under this level of contention. After the fix (defaultMaxAttempts=10, jittered
        // backoff 10–500 ms, busy_timeout=5000) all 400 increments land without error.
        test("concurrent read-then-write transactions succeed under WAL contention") {
            val tmp =
                Files.createTempFile("listenup-busy-snapshot-", ".db").also {
                    it.toFile().deleteOnExit()
                }
            val handle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.toAbsolutePath()}"))
            val db = handle.database

            // Create a counter table and seed the initial value.
            transaction(db) {
                exec("CREATE TABLE IF NOT EXISTS counters (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
                exec("INSERT INTO counters(id, value) VALUES (1, 0)")
            }

            val coroutines = 8
            val iterationsPerCoroutine = 50
            val expectedTotal = coroutines * iterationsPerCoroutine

            // Each coroutine does a read-then-write inside a single transaction (deferred
            // by default in SQLite).  Under WAL + concurrent writers, some transactions
            // will land on a snapshot that another connection has already advanced past;
            // those return SQLITE_BUSY_SNAPSHOT, which Exposed surfaces as a SQLException.
            coroutineScope {
                repeat(coroutines) {
                    launch(Dispatchers.IO) {
                        repeat(iterationsPerCoroutine) {
                            suspendTransaction(db) {
                                val current =
                                    exec("SELECT value FROM counters WHERE id = 1") { rs ->
                                        rs.next()
                                        rs.getInt(1)
                                    }!!
                                exec("UPDATE counters SET value = ${current + 1} WHERE id = 1")
                            }
                        }
                    }
                }
            }

            val finalCount =
                transaction(db) {
                    exec("SELECT value FROM counters WHERE id = 1") { rs ->
                        rs.next()
                        rs.getInt(1)
                    }!!
                }

            finalCount shouldBe expectedTotal
        }
    })
