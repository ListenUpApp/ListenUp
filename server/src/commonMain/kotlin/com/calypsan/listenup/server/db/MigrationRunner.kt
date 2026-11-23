package com.calypsan.listenup.server.db

import kotlin.time.Clock

internal class MigrationRunner(
    private val dbPath: String,
    private val catalog: List<Migration> = MigrationCatalog.all,
) {
    fun migrate(upTo: Int? = null): String? {
        openAdminConnection(dbPath, readOnly = false).use { conn ->
            ensureHistoryTable(conn)
            val applied = appliedVersions(conn)
            verifyAppliedKnown(applied)
            verifyChecksums(applied)
            catalog
                .filter { it.version !in applied.keys }
                .filter { upTo == null || it.version <= upTo }
                .sortedBy { it.version }
                .forEach { applyOne(conn, it) }
        }
        return currentSchemaVersion()
    }

    fun currentSchemaVersion(): String? =
        openAdminConnection(dbPath, readOnly = false).use { conn ->
            ensureHistoryTable(conn)
            conn
                .query("SELECT MAX(version) AS v FROM schema_migrations") { row -> row.getString("v") }
                .firstOrNull()
        }

    private fun ensureHistoryTable(conn: SqlAdminConnection) {
        conn.execute(
            "CREATE TABLE IF NOT EXISTS schema_migrations (" +
                "version INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                "checksum TEXT NOT NULL, applied_at TEXT NOT NULL)",
        )
    }

    private fun appliedVersions(conn: SqlAdminConnection): Map<Int, String> =
        conn
            .query("SELECT version, checksum FROM schema_migrations") { row ->
                row.getInt("version") to row.getString("checksum").orEmpty()
            }.toMap()

    private fun verifyAppliedKnown(applied: Map<Int, String>) {
        val knownVersions = catalog.mapTo(HashSet()) { it.version }
        val unknown = applied.keys.filter { it !in knownVersions }.sorted()
        if (unknown.isEmpty()) return

        val maxKnown = catalog.maxOfOrNull { it.version }
        val formatted = unknown.joinToString(", ") { "V$it" }
        if (maxKnown != null && unknown.any { it > maxKnown }) {
            error(
                "Database schema is newer than this server binary " +
                    "(applied unknown migration(s) $formatted; server supports up to V$maxKnown). " +
                    "Refusing to start against a downgraded server — upgrade the server binary " +
                    "or restore a matching backup.",
            )
        }
        error(
            "Database contains applied migration(s) missing from this server's catalog ($formatted). " +
                "Migrations are forward-only and cannot be removed after being applied.",
        )
    }

    private fun verifyChecksums(applied: Map<Int, String>) {
        for ((version, recorded) in applied) {
            val expected =
                checkNotNull(catalog.firstOrNull { it.version == version }) {
                    "Missing migration V$version in catalog after applied-version check"
                }.checksum
            check(expected == recorded) {
                "Migration V$version was edited after it was applied " +
                    "(checksum $recorded → $expected). Migrations are forward-only and immutable."
            }
        }
    }

    private fun applyOne(
        conn: SqlAdminConnection,
        migration: Migration,
    ) {
        try {
            conn.inTransaction {
                for (statement in SqlStatementSplitter.split(migration.sql)) conn.execute(statement)
                conn.query(
                    "INSERT INTO schema_migrations (version, name, checksum, applied_at) " +
                        "VALUES (?, ?, ?, ?) RETURNING version",
                    bind = {
                        bindString(1, migration.version.toString())
                        bindString(2, migration.name)
                        bindString(3, migration.checksum)
                        bindString(4, Clock.System.now().toString())
                    },
                ) { it.getInt("version") }
            }
        } catch (e: Throwable) {
            throw IllegalStateException("Migration V${migration.version} (${migration.name}) failed", e)
        }
    }
}
