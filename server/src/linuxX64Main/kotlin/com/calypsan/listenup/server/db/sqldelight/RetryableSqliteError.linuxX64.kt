package com.calypsan.listenup.server.db.sqldelight

/**
 * Native actual: SQLiter surfaces busy/locked as an exception whose message carries the SQLite text.
 * Match on the message across the cause chain (no SQLiter type import needed, so this always compiles
 * on linuxX64). The native server runs under low write-concurrency for now; tighten to a typed match
 * if a busy-snapshot storm is ever observed natively.
 */
actual fun Throwable.isRetryableSqliteError(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        val msg = cause.message?.uppercase().orEmpty()
        if ("SQLITE_BUSY" in msg || "DATABASE IS LOCKED" in msg || "DATABASE IS BUSY" in msg) {
            return true
        }
        cause = cause.cause
    }
    return false
}
