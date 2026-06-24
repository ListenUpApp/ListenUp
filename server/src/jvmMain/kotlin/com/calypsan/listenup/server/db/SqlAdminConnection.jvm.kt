package com.calypsan.listenup.server.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import org.sqlite.SQLiteConfig

internal actual fun openAdminConnection(
    dbPath: String,
    readOnly: Boolean,
): SqlAdminConnection {
    val config =
        SQLiteConfig().apply {
            busyTimeout = BUSY_TIMEOUT_MS
            if (!readOnly) setJournalMode(SQLiteConfig.JournalMode.WAL)
        }
    val url = if (readOnly) "jdbc:sqlite:file:$dbPath?mode=ro" else "jdbc:sqlite:$dbPath"
    val conn = java.sql.DriverManager.getConnection(url, config.toProperties())
    return JdbcAdminConnection(conn)
}

private const val BUSY_TIMEOUT_MS = 5_000

private class JdbcAdminConnection(
    private val conn: Connection,
) : SqlAdminConnection {
    override fun execute(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun <T> query(
        sql: String,
        bind: SqlBinder.() -> Unit,
        map: (SqlRow) -> T,
    ): List<T> =
        conn.prepareStatement(sql).use { ps ->
            JdbcBinder(ps).bind()
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(JdbcRow(rs))) }
            }
        }

    override fun <T> inTransaction(block: () -> T): T {
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() = conn.close()
}

private class JdbcBinder(
    private val ps: PreparedStatement,
) : SqlBinder {
    override fun bindString(
        index: Int,
        value: String?,
    ) = ps.setString(index, value)
}

private class JdbcRow(
    private val rs: ResultSet,
) : SqlRow {
    override fun getString(name: String): String? = rs.getString(name)

    override fun getDouble(name: String): Double = rs.getDouble(name)

    override fun getInt(name: String): Int = rs.getInt(name)

    override fun getBoolean(name: String): Boolean = rs.getBoolean(name)
}
