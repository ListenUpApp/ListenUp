@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.db

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import sqlite3.SQLITE_OK
import sqlite3.SQLITE_OPEN_CREATE
import sqlite3.SQLITE_OPEN_READONLY
import sqlite3.SQLITE_OPEN_READWRITE
import sqlite3.SQLITE_ROW
import sqlite3.SQLITE_TRANSIENT
import sqlite3.sqlite3_bind_text
import sqlite3.sqlite3_busy_timeout
import sqlite3.sqlite3_close
import sqlite3.sqlite3_column_count
import sqlite3.sqlite3_column_double
import sqlite3.sqlite3_column_int
import sqlite3.sqlite3_column_int64
import sqlite3.sqlite3_column_name
import sqlite3.sqlite3_column_text
import sqlite3.sqlite3_errmsg
import sqlite3.sqlite3_exec
import sqlite3.sqlite3_finalize
import sqlite3.sqlite3_open_v2
import sqlite3.sqlite3_prepare_v2
import sqlite3.sqlite3_step

private const val BUSY_TIMEOUT_MS = 5_000

internal actual fun openAdminConnection(
    dbPath: String,
    readOnly: Boolean,
): SqlAdminConnection {
    val flags = if (readOnly) SQLITE_OPEN_READONLY else SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE
    val dbPtr = nativeHeap.allocPointerTo<sqlite3>()
    val rc = sqlite3_open_v2(dbPath, dbPtr.ptr, flags.convert(), null)
    val db = dbPtr.value
    if (rc != SQLITE_OK || db == null) {
        val msg = db?.let { sqlite3_errmsg(it)?.toKString() } ?: "rc=$rc"
        if (db != null) sqlite3_close(db)
        nativeHeap.free(dbPtr)
        error("sqlite open failed for $dbPath: $msg")
    }
    sqlite3_busy_timeout(db, BUSY_TIMEOUT_MS)
    if (!readOnly) sqlite3_exec(db, "PRAGMA journal_mode=WAL;", null, null, null)
    return Sqlite3AdminConnection(db, dbPtr)
}

private class Sqlite3AdminConnection(
    private val db: CPointer<sqlite3>,
    private val dbPtr: CPointerVarOf<CPointer<sqlite3>>,
) : SqlAdminConnection {
    override fun execute(sql: String) {
        if (sqlite3_exec(db, sql, null, null, null) != SQLITE_OK) {
            error("sqlite exec failed: ${sqlite3_errmsg(db)?.toKString()} :: $sql")
        }
    }

    override fun <T> query(
        sql: String,
        bind: SqlBinder.() -> Unit,
        map: (SqlRow) -> T,
    ): List<T> =
        memScoped {
            val stmtPtr = alloc<CPointerVar<sqlite3_stmt>>()
            if (sqlite3_prepare_v2(db, sql, -1, stmtPtr.ptr, null) != SQLITE_OK) {
                error("sqlite prepare failed: ${sqlite3_errmsg(db)?.toKString()} :: $sql")
            }
            val stmt = stmtPtr.value ?: error("null stmt")
            try {
                Sqlite3Binder(stmt).bind()
                val columnCount = sqlite3_column_count(stmt)
                val nameToIndex =
                    (0 until columnCount).associateBy(
                        { sqlite3_column_name(stmt, it)!!.toKString() },
                        { it },
                    )
                val row = Sqlite3Row(stmt, nameToIndex)
                buildList { while (sqlite3_step(stmt) == SQLITE_ROW) add(map(row)) }
            } finally {
                sqlite3_finalize(stmt)
            }
        }

    override fun <T> inTransaction(block: () -> T): T {
        execute("BEGIN")
        try {
            val result = block()
            execute("COMMIT")
            return result
        } catch (e: Throwable) {
            // Best-effort rollback; the original exception is rethrown below.
            runCatching { execute("ROLLBACK") }.getOrNull()
            throw e
        }
    }

    override fun close() {
        sqlite3_close(db)
        nativeHeap.free(dbPtr)
    }
}

private class Sqlite3Binder(
    private val stmt: CPointer<sqlite3_stmt>,
) : SqlBinder {
    override fun bindString(
        index: Int,
        value: String?,
    ) {
        // The cinterop binding marshals `value` to a C string valid for this call; SQLITE_TRANSIENT
        // makes sqlite copy the bytes immediately, so no longer lifetime is required.
        sqlite3_bind_text(stmt, index, value, -1, SQLITE_TRANSIENT)
    }
}

private class Sqlite3Row(
    private val stmt: CPointer<sqlite3_stmt>,
    private val nameToIndex: Map<String, Int>,
) : SqlRow {
    private fun idx(name: String) = nameToIndex[name] ?: error("no such column: $name")

    override fun getString(name: String): String? =
        sqlite3_column_text(stmt, idx(name))?.reinterpret<ByteVar>()?.toKString()

    override fun getDouble(name: String): Double = sqlite3_column_double(stmt, idx(name))

    override fun getInt(name: String): Int = sqlite3_column_int(stmt, idx(name))

    override fun getBoolean(name: String): Boolean = sqlite3_column_int64(stmt, idx(name)) != 0L
}
