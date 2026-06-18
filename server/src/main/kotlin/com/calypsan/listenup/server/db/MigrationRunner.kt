package com.calypsan.listenup.server.db

import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

/**
 * Applies the embedded [MigrationCatalog] forward against a SQLite [DataSource], tracking
 * applied versions in a `schema_migrations` table it owns. Forward-only; each migration runs
 * in its own transaction. Replaces Flyway — no classpath scanning, no history-table adoption.
 */
internal class MigrationRunner(
    private val dataSource: DataSource,
    private val catalog: List<Migration> = MigrationCatalog.all,
) {
    /** Applies pending migrations (optionally only those `version <= upTo`). Returns the new version. */
    fun migrate(upTo: Int? = null): String? {
        dataSource.connection.use { conn ->
            ensureHistoryTable(conn)
            val applied = appliedVersions(conn)
            verifyChecksums(applied)
            val pending =
                catalog
                    .filter { it.version !in applied.keys }
                    .filter { upTo == null || it.version <= upTo }
                    .sortedBy { it.version }
            for (migration in pending) {
                applyOne(conn, migration)
            }
        }
        return currentSchemaVersion()
    }

    /** The highest applied version as a string, or null when none are applied. */
    fun currentSchemaVersion(): String? =
        dataSource.connection.use { conn ->
            ensureHistoryTable(conn)
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT MAX(version) FROM schema_migrations").use { rs ->
                    if (rs.next()) {
                        val v = rs.getInt(1)
                        if (rs.wasNull()) null else v.toString()
                    } else {
                        null
                    }
                }
            }
        }

    private fun ensureHistoryTable(conn: Connection) {
        conn.createStatement().use {
            it.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations (" +
                    "version INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                    "checksum TEXT NOT NULL, applied_at TEXT NOT NULL)",
            )
        }
        conn.commit()
    }

    private fun appliedVersions(conn: Connection): Map<Int, String> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT version, checksum FROM schema_migrations").use { rs ->
                buildMap { while (rs.next()) put(rs.getInt(1), rs.getString(2)) }
            }
        }

    private fun verifyChecksums(applied: Map<Int, String>) {
        for ((version, recorded) in applied) {
            val expected = catalog.firstOrNull { it.version == version }?.checksum ?: continue
            check(expected == recorded) {
                "Migration V$version was edited after it was applied " +
                    "(checksum $recorded → $expected). Migrations are forward-only and immutable."
            }
        }
    }

    private fun applyOne(
        conn: Connection,
        migration: Migration,
    ) {
        try {
            for (statement in SqlStatementSplitter.split(migration.sql)) {
                conn.createStatement().use { it.execute(statement) }
            }
            conn.prepareStatement(
                "INSERT INTO schema_migrations (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setInt(1, migration.version)
                ps.setString(2, migration.name)
                ps.setString(3, migration.checksum)
                ps.setString(4, Instant.now().toString())
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw IllegalStateException("Migration V${migration.version} (${migration.name}) failed", e)
        }
    }
}
