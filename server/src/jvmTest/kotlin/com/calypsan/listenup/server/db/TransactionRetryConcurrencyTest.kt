package com.calypsan.listenup.server.db

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Fail fast rather than hang if the interleaving is mis-wired. */
private const val HANDSHAKE_TIMEOUT_SECONDS = 30L

/**
 * Proves end-to-end that a transaction which hits SQLITE_BUSY_SNAPSHOT is retried by
 * [suspendTransaction] and that its write still lands.
 *
 * The busy-snapshot is **forced**, not hoped for. The previous version of this test launched 4×25
 * concurrent read-then-write transactions and relied on natural contention to produce the error and on
 * the retry budget to absorb it — an assumption about machine load, which failed on a loaded CI runner
 * (the budget ran out and the raw SQLiteException escaped). A gate whose pass depends on how busy the
 * runner is proves nothing about the code. Here the interleaving is exact:
 *
 *  1. the reader opens a deferred transaction and SELECTs — taking a read snapshot;
 *  2. a second connection commits a write, advancing the database past that snapshot;
 *  3. the reader then attempts its own write — SQLite rejects the snapshot upgrade *immediately* with
 *     SQLITE_BUSY_SNAPSHOT (extended result code 517; sqlite-jdbc reports the primary code 5 and names
 *     the extended one in the message), by design and regardless of load.
 *
 * The retry must then re-run the whole transaction against the current snapshot, re-read the value the
 * other connection wrote, and commit — so both increments land (no lost update).
 *
 * Why a file-backed DB, not `:memory:`? Each SQLite connection opens its own handle; in `:memory:` mode
 * every handle gets a private, independent database — no shared WAL, nothing to contend on. File-backed
 * WAL is the only mode where multiple connections share one database. `JdbcSqliteDriver` binds a
 * connection per thread for a file URL, so the two transaction bodies below — which run on distinct
 * threads, the reader's being blocked while the writer works — genuinely use two connections.
 *
 * `busy_timeout` does NOT cure a busy-snapshot (it only waits for a write lock; a stale snapshot is
 * rejected outright); re-running the transaction is the only cure. The classification of which errors
 * are retryable is unit-tested separately in RetryableSqliteErrorTest — this test's job is the
 * end-to-end retry-and-land behaviour.
 */
class TransactionRetryConcurrencyTest :
    FunSpec({
        test("a busy-snapshot transaction is retried against a fresh snapshot and its write still lands") {
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

            fun increment(current: Long) = driver.execute(null, "UPDATE counters SET value = ${current + 1} WHERE id = 1", 0)

            // Seed the counter table (raw SQL over the driver).
            driver.execute(null, "CREATE TABLE IF NOT EXISTS counters (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)", 0)
            driver.execute(null, "INSERT INTO counters(id, value) VALUES (1, 0)", 0)

            // The handshake. The reader's transaction body is synchronous, so it signals with a
            // (non-suspending) CompletableDeferred.complete and waits on a latch; the writer coroutine
            // awaits the Deferred and counts the latch down. No sleeps, no timing assumptions.
            val snapshotTaken = CompletableDeferred<Unit>()
            val writerCommitted = CountDownLatch(1)
            val readerAttempts = AtomicInteger(0)
            val firstFailure = AtomicReference<Throwable?>(null)

            coroutineScope {
                launch(Dispatchers.IO) {
                    suspendTransaction(db) {
                        val attempt = readerAttempts.incrementAndGet()
                        val current = readCounter() // takes this transaction's read snapshot
                        if (attempt == 1) {
                            // Hand over to the writer, then resume onto a snapshot it has superseded.
                            snapshotTaken.complete(Unit)
                            check(writerCommitted.await(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                "writer never committed — the busy-snapshot interleaving did not happen"
                            }
                        }
                        try {
                            increment(current)
                        } catch (e: Throwable) {
                            // Recorded, then re-thrown untouched so suspendTransaction still owns the retry.
                            firstFailure.compareAndSet(null, e)
                            throw e
                        }
                    }
                }
                launch(Dispatchers.IO) {
                    snapshotTaken.await()
                    suspendTransaction(db) { increment(readCounter()) }
                    writerCommitted.countDown()
                }
            }

            // The retry is the point, and so is WHICH error forced it. Asserting only the final count
            // would pass vacuously if the busy-snapshot never occurred; asserting only the attempt count
            // would accept any retryable error. Both are pinned.
            firstFailure
                .get()
                .let { failure -> generateSequence(failure) { it.cause } }
                .any { it.message?.contains("SQLITE_BUSY_SNAPSHOT") == true } shouldBe true
            readerAttempts.get() shouldBe 2
            // Both increments landed — the retry re-read the writer's value rather than clobbering it.
            readCounter() shouldBe 2L
            handle.close()
        }
    })
