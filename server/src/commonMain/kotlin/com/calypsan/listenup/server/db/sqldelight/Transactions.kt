package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.TransactionWithReturn
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Max attempts for a transaction that keeps hitting a transient SQLite busy/snapshot error. */
private const val MAX_TX_ATTEMPTS = 10

/** Jitter bounds (ms) between retry attempts — mirrors the former Exposed retry config. */
private const val MIN_RETRY_DELAY_MS = 10L
private const val MAX_RETRY_DELAY_MS = 500L

/**
 * Runs [body] inside a SQLDelight [TransactionWithReturn] on [sqlIoDispatcher], returning [body]'s
 * result on commit. Mirrors Exposed's `suspendTransaction`: the transaction commits on normal return
 * (including a returned [com.calypsan.listenup.api.result.AppResult.Failure]); an exception thrown from
 * [body] rolls back and re-throws.
 *
 * Retries the WHOLE transaction up to [MAX_TX_ATTEMPTS] times when it fails with a transient
 * [isRetryableSqliteError] (SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT), with a jittered backoff between
 * attempts. This replaces the retry Exposed's `DatabaseConfig` used to provide; `busy_timeout` on the
 * driver covers plain SQLITE_BUSY, this covers SQLITE_BUSY_SNAPSHOT (returned immediately, snapshot
 * stale). A [CancellationException] is never retried.
 *
 * The [ListenUpDatabase] implements the synchronous [app.cash.sqldelight.Transacter] — there is no
 * suspending variant — so the [withContext] wrapper is the canonical way to keep coroutine dispatch
 * honest. [sqlIoDispatcher] is an expect/actual that resolves to [kotlinx.coroutines.Dispatchers.IO]
 * on JVM and [kotlinx.coroutines.Dispatchers.Default] on Kotlin/Native.
 */
suspend fun <T> suspendTransaction(
    db: ListenUpDatabase,
    body: TransactionWithReturn<T>.() -> T,
): T =
    withContext(sqlIoDispatcher) {
        var lastError: Throwable? = null
        repeat(MAX_TX_ATTEMPTS) { attempt ->
            try {
                return@withContext db.transactionWithResult { body() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!e.isRetryableSqliteError()) throw e
                lastError = e
                if (attempt < MAX_TX_ATTEMPTS - 1) {
                    delay(Random.nextLong(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("suspendTransaction retry loop exited without a result")
    }
