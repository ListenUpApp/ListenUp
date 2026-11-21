package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import kotlin.concurrent.Volatile

/**
 * Owns the repos' restore-swappable [SwappableSqlDriver] and the db file path, so the restore
 * orchestrator can close the driver, swap the file in place, reopen, and migrate forward while keeping
 * the [SwappableSqlDriver] (and every repository bound to it) identity-stable. Migration / VACUUM open a
 * short-lived admin connection on [dbPath] per call.
 */
class DatabaseHandle(
    val sqlDriver: SwappableSqlDriver,
    val dbPath: String,
) {
    @Volatile
    private var closed = false

    /** Transactionally-consistent standalone copy at [targetPath] (WAL-safe; `VACUUM INTO` runs outside a tx). */
    fun vacuumInto(targetPath: String) {
        val escaped = targetPath.replace("'", "''")
        openAdminConnection(dbPath, readOnly = false).use { it.execute("VACUUM INTO '$escaped'") }
    }

    /** Hard-closes the repos' SQLDelight driver before the db file is swapped. Pair with [reopenPool]. */
    fun closePool() {
        sqlDriver.closeUnderlying()
    }

    /** Rebuilds the SQLDelight driver on the (possibly just-swapped) db file; driver identity preserved. */
    fun reopenPool() {
        sqlDriver.installUnderlying(DriverFactory().createDriver(dbPath))
    }

    /** The sync revision counter in the db file as it stands, or null when the row is absent. */
    fun revisionCounter(): Long? =
        openAdminConnection(dbPath, readOnly = true).use { conn ->
            conn
                .query("SELECT value FROM sync_meta WHERE key = 'revision_counter'") { it.getString("value") }
                .firstOrNull()
                ?.toLongOrNull()
        }

    /**
     * Renumbers every row of a just-restored db file above [floor] and moves the counter past the last
     * one. Every client remembers the highest revision it has seen and refuses a row older than the one
     * it holds, so restored rows still carrying their pre-backup revisions would be skipped by catch-up
     * and invisible to the firehose.
     *
     * Every column whose name contains `revision` shifts by one offset, chosen above [floor], the file's
     * own counter and every value in those columns. One offset keeps rows in their original order and
     * keeps columns that compare against each other equal (a match receipt's `revision_after` is its
     * book's `revision` while Undo is offered). The columns come from the schema itself, so a new
     * syncable table is covered without being listed here. Runs in one transaction on an admin
     * connection, while the pool is closed.
     */
    fun restampRevisionsAbove(floor: Long) {
        openAdminConnection(dbPath, readOnly = false).use { conn ->
            conn.inTransaction {
                val columns =
                    conn.query(REVISION_COLUMNS_SQL) { row ->
                        RevisionColumn(checkNotNull(row.getString("tbl")), checkNotNull(row.getString("col")))
                    }
                val counter = conn.longOrNull(REVISION_COUNTER_SQL) ?: 0L
                val highest = columns.maxOfOrNull { conn.highestIn(it) } ?: 0L
                val offset = maxOf(floor, counter, highest) + 1
                // Every interpolated value is a Long or a name read from sqlite_master, never caller text.
                columns.forEach { column ->
                    conn.execute(
                        "UPDATE ${column.quotedTable} SET ${column.quotedColumn} = ${column.quotedColumn} + $offset",
                    )
                }
                conn.execute("UPDATE sync_meta SET value = ${offset + highest} WHERE key = 'revision_counter'")
            }
        }
    }

    /** Runs migrations forward against the current db file. Returns the post-migration version. */
    fun migrate(): String? = MigrationRunner(dbPath).migrate()

    /** The applied schema version, or null on a fresh/empty db. */
    fun currentSchemaVersion(): String? = MigrationRunner(dbPath).currentSchemaVersion()

    /** Terminal shutdown — closes the SQLDelight driver; nothing reopens. Idempotent. */
    fun close() {
        closed = true
        sqlDriver.close()
    }

    /** True once [close] has run. Test-observability for graceful shutdown. */
    fun isPoolClosed(): Boolean = closed
}

/** A column holding a sync revision, as [DatabaseHandle.restampRevisionsAbove] reads it from the schema. */
private data class RevisionColumn(
    val table: String,
    val column: String,
) {
    val quotedTable: String get() = "\"$table\""
    val quotedColumn: String get() = "\"$column\""
}

private const val REVISION_COLUMNS_SQL =
    "SELECT m.name AS tbl, p.name AS col FROM sqlite_master m, pragma_table_info(m.name) p " +
        "WHERE m.type = 'table' AND m.name NOT LIKE 'sqlite_%' AND p.name LIKE '%revision%' ORDER BY m.name, p.name"

private const val REVISION_COUNTER_SQL = "SELECT value AS v FROM sync_meta WHERE key = 'revision_counter'"

/** The largest value in [column], or 0 when the table is empty. */
private fun SqlAdminConnection.highestIn(column: RevisionColumn): Long =
    longOrNull("SELECT MAX(${column.quotedColumn}) AS v FROM ${column.quotedTable}") ?: 0L

/** The first row's `v` column as a Long, or null when there is no row or the value is NULL. */
private fun SqlAdminConnection.longOrNull(sql: String): Long? =
    query(sql) { it.getString("v") }.firstOrNull()?.toLongOrNull()
