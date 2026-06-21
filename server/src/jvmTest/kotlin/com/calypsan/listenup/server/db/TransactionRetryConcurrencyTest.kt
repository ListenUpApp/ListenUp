package com.calypsan.listenup.server.db

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Files

/**
 * Stress-tests SQLite concurrency under WAL to reproduce SQLITE_BUSY_SNAPSHOT and verify the retry in
 * [suspendTransaction] clears it.
 *
 * Why a file-backed DB, not `:memory:`? Each SQLite connection opens its own handle; in `:memory:` mode
 * every handle gets a private, independent database — no shared WAL, nothing to contend on. File-backed
 * WAL is the only mode where multiple connections share one database and race to commit.
 *
 * The BUSY_SNAPSHOT error occurs when a deferred transaction reads a snapshot already superseded by a
 * committed write from another connection. `busy_timeout` does NOT cure it (it only waits for a write
 * lock; a snapshot mismatch is rejected immediately). The only cure is to re-run the whole transaction
 * against the current snapshot — which [suspendTransaction] now does (up to 10 attempts, jittered backoff),
 * replacing the retry Exposed's `DatabaseConfig` used to provide. The read-then-increment also proves the
 * retry re-reads: a stale-snapshot write fails and is retried, so no increment is lost.
 */
class TransactionRetryConcurrencyTest :
    FunSpec({
        test("concurrent read-then-write transactions succeed under WAL contention (busy-snapshot retried)") {
            val tmp = Files.createTempFile("listenup-busy-snapshot-", ".db").also { it.toFile().deleteOnExit() }
            val handle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.toAbsolutePath()}"))
            val driver = handle.sqlDriver
            val db = ListenUpDatabase(driver)

            fun readCounter(): Long =
                driver
                    .executeQuery(
                        identifier = null,
                        sql = "SELECT value FROM counters WHERE id = 1",
                        mapper = { cursor ->
                            cursor.next()
                            QueryResult.Value(cursor.getLong(0)!!)
                        },
                        parameters = 0,
                    ).value

            // Seed the counter table (raw SQL over the driver).
            driver.execute(null, "CREATE TABLE IF NOT EXISTS counters (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)", 0)
            driver.execute(null, "INSERT INTO counters(id, value) VALUES (1, 0)", 0)

            val coroutines = 8
            val iterationsPerCoroutine = 50
            val expectedTotal = (coroutines * iterationsPerCoroutine).toLong()

            // Each coroutine does a read-then-write inside one (deferred) transaction. Under WAL + concurrent
            // writers, some land on a snapshot another connection has advanced past → SQLITE_BUSY_SNAPSHOT,
            // which suspendTransaction retries. All increments must land (no loss) ⇒ count == expectedTotal.
            coroutineScope {
                repeat(coroutines) {
                    launch(Dispatchers.IO) {
                        repeat(iterationsPerCoroutine) {
                            suspendTransaction(db) {
                                val current = readCounter()
                                driver.execute(null, "UPDATE counters SET value = ${current + 1} WHERE id = 1", 0)
                            }
                        }
                    }
                }
            }

            readCounter() shouldBe expectedTotal
            handle.close()
        }
    })
