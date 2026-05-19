package com.calypsan.listenup.client.data.local.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Runs a series of DAO writes inside a single SQLite write transaction so they either all
 * commit or all roll back (Finding 05 D2 of the architecture audit).
 *
 * Every call site that issues more than one DAO write for a single logical operation —
 * pullers, sync event processors, multi-entity edit repositories, library reset — must
 * route its writes through this seam.
 *
 * Exclude disk and network I/O from [atomically]'s block. The underlying write connection
 * is SQLite's single serialisation point; holding it longer than necessary stalls every
 * other pending write in the app.
 */
interface TransactionRunner {
    suspend fun <R> atomically(block: suspend () -> R): R
}

/**
 * Production [TransactionRunner]: delegates to Room's IMMEDIATE write transaction on the
 * [ListenUpDatabase] writer connection. Any exception thrown inside [atomically] —
 * including `CancellationException` — aborts the transaction. DAO calls made inside the
 * block detect the held transaction and become savepoint-scoped nested transactions, so
 * Room's invalidation tracker fires correctly when the outer block commits.
 */
class RoomTransactionRunner(
    private val database: ListenUpDatabase,
) : TransactionRunner {
    override suspend fun <R> atomically(block: suspend () -> R): R =
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }
}
