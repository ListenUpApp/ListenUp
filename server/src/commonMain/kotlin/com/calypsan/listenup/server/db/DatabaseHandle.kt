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
     * Raises the revision counter in the db file to at least [floor]. Never lowers it: every client
     * remembers the highest revision it has seen, and a write numbered below that is invisible to it.
     */
    fun raiseRevisionCounterTo(floor: Long) {
        openAdminConnection(dbPath, readOnly = false).use { conn ->
            // floor is a Long read from the database, never caller text, so interpolation is safe.
            conn.execute("UPDATE sync_meta SET value = MAX(value, $floor) WHERE key = 'revision_counter'")
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
