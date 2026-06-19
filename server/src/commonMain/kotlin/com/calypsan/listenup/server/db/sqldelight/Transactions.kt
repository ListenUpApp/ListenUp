package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.TransactionWithReturn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [body] inside a SQLDelight [TransactionWithReturn] on [Dispatchers.IO], returning
 * [body]'s result on commit. Mirrors the semantics of Exposed's `suspendTransaction`:
 * the transaction commits on normal return (including a returned [AppResult.Failure]);
 * an exception thrown from [body] rolls back and re-throws.
 *
 * Use this helper in every SQLDelight repository method so blocking JDBC I/O is always
 * dispatched off the calling coroutine's thread. The [ListenUpDatabase] implements the
 * synchronous [app.cash.sqldelight.Transacter] — there is no suspending variant — so
 * the [withContext] wrapper is the canonical way to keep coroutine dispatch honest.
 */
suspend fun <T> suspendTransaction(
    db: ListenUpDatabase,
    body: TransactionWithReturn<T>.() -> T,
): T = withContext(Dispatchers.IO) { db.transactionWithResult { body() } }
