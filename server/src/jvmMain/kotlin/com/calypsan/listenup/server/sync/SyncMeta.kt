package com.calypsan.listenup.server.sync

import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

private const val COUNTER_KEY = "revision_counter"

/**
 * Atomically increment the revision counter and return the new value.
 *
 * Implemented via SQLite's `RETURNING` clause (3.35+) so the SELECT-then-UPDATE
 * race is collapsed into a single statement holding the row's write lock for
 * its full duration. Concurrent callers serialize behind that lock and each
 * receives a unique, monotonic value — no SQLITE_BUSY exceptions surface for
 * realistic write fan-in on the same `sync_meta` row.
 *
 * Must be called inside a transaction (compile-enforced via the [JdbcTransaction]
 * receiver). Composes inside the parent transaction so the revision bump is
 * atomic with the row write that consumes it.
 *
 * `explicitStatementType = EXEC` forces Exposed to read the `RETURNING` result
 * via `executeQuery()` — without it, Exposed routes `UPDATE` through
 * `executeUpdate()` which discards the result set on most JDBC drivers.
 */
internal fun JdbcTransaction.nextRevision(): Long {
    val result =
        exec(
            stmt = "UPDATE sync_meta SET value = value + 1 WHERE key = ? RETURNING value",
            args = listOf(TextColumnType() to COUNTER_KEY),
            explicitStatementType = StatementType.EXEC,
        ) { rs ->
            check(rs.next()) { "sync_meta missing $COUNTER_KEY row" }
            rs.getLong(1)
        }
    return checkNotNull(result) { "RETURNING clause produced no result set" }
}
