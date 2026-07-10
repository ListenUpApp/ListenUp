package com.calypsan.listenup.server.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Schema drift-gate: every table the SQLDelight `.sq` set declares must agree with the
 * authoritative schema [MigrationRunner] produces from the `db/migration` SQL files.
 *
 * Both halves are reflected through `PRAGMA table_info` / `PRAGMA index_list` /
 * `PRAGMA index_info` against a real SQLite database — the migration side after running
 * every migration, the SQLDelight side after `ListenUpDatabase.Schema.create`. Reflecting
 * through PRAGMAs (rather than diffing CREATE-statement DDL) is what makes the comparison
 * robust against the deliberate, documented `.sq` ⇄ migration divergences:
 *
 *  1. **FK declarations omitted in `.sq`** — SQLDelight can't resolve cross-`.sq` refs, so the
 *     `.sq` drops `REFERENCES` / `FOREIGN KEY`. `PRAGMA table_info` never reports FKs, so they
 *     fall out of the comparison automatically.
 *  2. **`NOT NULL` vs nullable / `DEFAULT`-backfilled** — columns like `revision` / `created_at`
 *     / `updated_at` / `folder_id` / `scanned_at` are declared `NOT NULL` in `.sq` but are added
 *     nullable (or via a `DEFAULT`-bearing `ALTER`) in the migrations. We compare the column's
 *     **type category only**, never the `notnull` flag or `dflt_value`, so backfilled-but-always-
 *     written columns read as agreement.
 *  3. **`VARCHAR(n)` → `TEXT`, `BOOLEAN` → `INTEGER`** — normalized to a SQLite storage class
 *     (TEXT / INTEGER / REAL / BLOB) via SQLite's own type-affinity rules.
 *  4. **Default-value clauses and column order** — `dflt_value` is ignored and columns are
 *     compared as a name→category map, so neither order nor defaults register as drift.
 *
 * What this DOES catch — the drift that matters during an Exposed→SQLDelight conversion:
 *  - a column present on one side but missing on the other,
 *  - a genuine type-category mismatch (TEXT where the migration has INTEGER, etc.),
 *  - a missing or extra index (compared by its indexed-column-set + uniqueness, so PK/UNIQUE
 *    auto-index naming differences between the two submitters don't register as drift).
 *
 * Comparison strategy chosen: **column-name-set + type-category + index-set** (the task's
 * accepted robust target), not full-DDL normalization — DDL normalization is too noisy given
 * divergences 1–4, whereas PRAGMA reflection makes each of them structurally invisible while
 * keeping the real signal sharp.
 */
class SqlDelightMigrationSchemaDriftTest :
    FunSpec({

        test("every `.sq` table's schema agrees with the MigrationRunner schema") {
            migrationConnection().use { migration ->
                sqlDelightConnection().use { sqlDelight ->
                    val migrationTables = tablesIn(migration)

                    // Sanity: the migration must actually define every table the `.sq` set claims,
                    // otherwise a typo'd table name would silently pass.
                    val missingFromMigration = SQ_TABLES.filterNot { it in migrationTables }
                    withClue("`.sq` tables absent from the migration schema (typo or dropped table)") {
                        missingFromMigration shouldBe emptyList()
                    }

                    SQ_TABLES.forEach { table ->
                        val expected = reflectTable(migration, table)
                        val actual = reflectTable(sqlDelight, table)

                        // One map comparison covers both the column-name set and each column's
                        // type category; the diff names the exact column on a mismatch.
                        withClue("column / type-category drift in `$table` (.sq vs migration)") {
                            actual.columns shouldBe expected.columns
                        }
                        withClue("index set drift in `$table` (indexed-column-set + uniqueness)") {
                            actual.indexes shouldBe expected.indexes
                        }
                    }
                }
            }
        }
    })

/**
 * The tables the SQLDelight `.sq` set declares — every Exposed→SQLDelight conversion (including the
 * genre catalog: `genres` + `genre_aliases` + `pending_book_genres`) plus the `book_search` FTS5
 * cluster. Kept as an explicit literal so adding a new `.sq` table is a deliberate edit here, and a
 * table silently dropped from the `.sq` set surfaces as a coverage gap rather than a vanished assertion.
 */
private val SQ_TABLES =
    listOf(
        "tags",
        "book_tags",
        "contributors",
        "contributor_aliases",
        "book_series",
        "books",
        "book_contributors",
        "book_series_memberships",
        "book_chapters",
        "book_audio_files",
        "book_documents",
        "book_genres",
        "book_search",
        "book_search_map",
        "moods",
        "book_moods",
        "shelves",
        "shelf_books",
        "libraries",
        "library_folders",
        "metadata_cache",
        "collections",
        "collection_books",
        "collection_grants",
        "genres",
        "genre_aliases",
        "pending_book_genres",
        "sync_meta",
        "user_stats",
        "listening_events",
        "playback_positions",
        "public_profiles",
        "admin_user_roster",
        "activities",
        "book_reads",
        "users",
        "sessions",
        "active_sessions",
        "invites",
        "push_tokens",
    )

/** A table's comparable shape: column → storage-class category, and the set of its indexes. */
private data class TableShape(
    val columns: Map<String, String>,
    val indexes: Set<IndexShape>,
)

/** An index reduced to what matters for drift: its indexed columns (in order) and uniqueness. */
private data class IndexShape(
    val columns: List<String>,
    val unique: Boolean,
)

/**
 * Reflects [table] into a [TableShape] via SQLite PRAGMAs. Auto-indexes from PK/UNIQUE
 * constraints are folded into the index set the same way on both sides (they carry the same
 * indexed columns), so the only difference a `sqlite_autoindex_*` naming gap could produce is
 * cancelled by comparing column-sets rather than names.
 */
private fun reflectTable(
    conn: Connection,
    table: String,
): TableShape = TableShape(columns = reflectColumns(conn, table), indexes = reflectIndexes(conn, table))

/** column name → normalized storage-class category (TEXT / INTEGER / REAL / BLOB / NUMERIC). */
private fun reflectColumns(
    conn: Connection,
    table: String,
): Map<String, String> =
    conn.createStatement().use { stmt ->
        stmt.executeQuery("PRAGMA table_info('${table.replace("'", "''")}')").use { rs ->
            buildMap {
                while (rs.next()) {
                    put(rs.getString("name"), storageClass(rs.getString("type")))
                }
            }
        }
    }

private fun reflectIndexes(
    conn: Connection,
    table: String,
): Set<IndexShape> =
    indexNames(conn, table)
        .map { (name, unique) -> IndexShape(columns = indexedColumns(conn, name), unique = unique) }
        .toSet()

private fun indexNames(
    conn: Connection,
    table: String,
): List<Pair<String, Boolean>> =
    conn.createStatement().use { stmt ->
        stmt.executeQuery("PRAGMA index_list('${table.replace("'", "''")}')").use { rs ->
            buildList {
                while (rs.next()) {
                    add(rs.getString("name") to (rs.getInt("unique") == 1))
                }
            }
        }
    }

private fun indexedColumns(
    conn: Connection,
    indexName: String,
): List<String> =
    conn.createStatement().use { stmt ->
        stmt.executeQuery("PRAGMA index_info('${indexName.replace("'", "''")}')").use { rs ->
            buildList {
                while (rs.next()) add(rs.getString("name"))
            }
        }
    }

/**
 * Collapses a declared column type to the storage-class category we compare on. Follows SQLite's
 * type-affinity rules (column-affinity spec §3.1) with one deliberate addition: `BOOL`/`BOOLEAN`
 * maps to INTEGER rather than its literal NUMERIC affinity.
 *
 * That `BOOLEAN → INTEGER` mapping is the documented divergence #3: the migration declares
 * `books.abridged` / `books.explicit` as `BOOLEAN`, while `Books.sq` declares them `INTEGER`
 * (0/1, with the repo mapping 0/1 ↔ Boolean at the boundary). Both are integer 0/1 columns; the
 * `BOOLEAN` spelling is cosmetic, so folding it to INTEGER treats them as the agreement they are.
 * `VARCHAR(n)` → TEXT and `BIGINT` → INTEGER fall out of the standard affinity rules below.
 */
private fun storageClass(declaredType: String?): String {
    val t = declaredType.orEmpty().uppercase()
    return when {
        t.contains("BOOL") -> "INTEGER"
        t.contains("INT") -> "INTEGER"
        t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") -> "TEXT"
        t.isBlank() || t.contains("BLOB") -> "BLOB"
        t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") -> "REAL"
        else -> "NUMERIC"
    }
}

private fun tablesIn(conn: Connection): Set<String> =
    conn.createStatement().use { stmt ->
        stmt
            .executeQuery(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%'",
            ).use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
    }

/** Authoritative side: a fresh SQLite with every migration applied, exposed as a plain JDBC connection. */
private fun migrationConnection(): Connection {
    val tmp = Files.createTempFile("listenup-drift-migration-", ".db").toFile().apply { deleteOnExit() }
    MigrationRunner(tmp.absolutePath).migrate()
    return DriverManager.getConnection("jdbc:sqlite:${tmp.absolutePath}")
}

/** `.sq` side: a fresh SQLite built by `ListenUpDatabase.Schema.create`, exposed as a plain JDBC connection. */
private fun sqlDelightConnection(): Connection {
    val tmp = Files.createTempFile("listenup-drift-sqldelight-", ".db").toFile().apply { deleteOnExit() }
    val driver = JdbcSqliteDriver("jdbc:sqlite:${tmp.absolutePath}")
    driver.use { ListenUpDatabase.Schema.create(it).value }
    return DriverManager.getConnection("jdbc:sqlite:${tmp.absolutePath}")
}
