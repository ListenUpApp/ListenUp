package com.calypsan.listenup.server.db.sqldelight

import java.sql.SQLException

private const val SQLITE_BUSY = 5
private const val SQLITE_BUSY_SNAPSHOT = 517

/**
 * JVM actual: walks the cause chain for a [SQLException] whose SQLite result code is `SQLITE_BUSY` (5)
 * or `SQLITE_BUSY_SNAPSHOT` (517). sqlite-jdbc puts the primary result code in [SQLException.errorCode].
 */
actual fun Throwable.isRetryableSqliteError(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is SQLException && (cause.errorCode == SQLITE_BUSY || cause.errorCode == SQLITE_BUSY_SNAPSHOT)) {
            return true
        }
        cause = cause.cause
    }
    return false
}
