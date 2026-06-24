package com.calypsan.listenup.server.db

/**
 * A short-lived connection to a SQLite file for admin SQL — migrations, `VACUUM INTO`, and reads of
 * external (non-app) SQLite files. Opened per operation via [openAdminConnection]; the caller closes it
 * (use `.use { }`). NOT for app runtime queries — those go through the SQLDelight [SwappableSqlDriver].
 */
internal interface SqlAdminConnection : AutoCloseable {
    /** Executes one DDL/DML statement with no result rows (auto-committed unless inside [inTransaction]). */
    fun execute(sql: String)

    /** Runs [sql], binding 1-based positional params via [bind], mapping each result row via [map]. */
    fun <T> query(
        sql: String,
        bind: SqlBinder.() -> Unit = {},
        map: (SqlRow) -> T,
    ): List<T>

    /** Runs [block] in a transaction: commits on success, rolls back if [block] throws. */
    fun <T> inTransaction(block: () -> T): T
}

/** 1-based positional parameter binding (matches JDBC `setString` and `sqlite3_bind_text`). */
internal interface SqlBinder {
    fun bindString(
        index: Int,
        value: String?,
    )
}

/** Null-aware column access by name (matches the ABS reader's `rs.getString("alias")` style). */
internal interface SqlRow {
    fun getString(name: String): String?

    fun getDouble(name: String): Double

    fun getInt(name: String): Int

    fun getBoolean(name: String): Boolean
}

/**
 * Opens a [SqlAdminConnection] on the SQLite file at [dbPath]. [readOnly] = true maps to JDBC
 * `?mode=ro` / `SQLITE_OPEN_READONLY` (external reads); read-write opens create the file if absent and
 * use WAL + a 5s busy timeout (migrations / VACUUM on the app DB). Throws on open failure.
 */
internal expect fun openAdminConnection(
    dbPath: String,
    readOnly: Boolean,
): SqlAdminConnection
