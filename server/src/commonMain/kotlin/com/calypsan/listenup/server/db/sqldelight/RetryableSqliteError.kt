package com.calypsan.listenup.server.db.sqldelight

/**
 * True when [this] (or any throwable in its cause chain) is a transient SQLite busy/locked error
 * that a transaction retry can clear — `SQLITE_BUSY` (5) or `SQLITE_BUSY_SNAPSHOT` (517).
 *
 * `busy_timeout` (set on the driver) already waits out ordinary SQLITE_BUSY; SQLITE_BUSY_SNAPSHOT is
 * returned immediately regardless of `busy_timeout` (the deferred snapshot is stale, not locked) — the
 * only cure is to re-run the transaction against the current snapshot, which [suspendTransaction] does.
 *
 * A non-busy error (constraint violation, syntax error, etc.) MUST return false so real failures are
 * never silently retried.
 */
expect fun Throwable.isRetryableSqliteError(): Boolean
