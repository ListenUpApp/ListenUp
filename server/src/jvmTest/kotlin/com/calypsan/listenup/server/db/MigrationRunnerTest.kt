package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

/** Returns a (dbPath, DataSource) pair for a fresh temp-file SQLite database. */
private fun freshDb(): Pair<String, DataSource> {
    val tmp = Files.createTempFile("listenup-runner-test-", ".db").toFile().apply { deleteOnExit() }
    val path = tmp.absolutePath
    return path to fileBackedTestDataSource("jdbc:sqlite:$path")
}

private fun DataSource.tableExists(name: String): Boolean =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$name'").use { it.next() }
        }
    }

/**
 * The DB's schema as a normalized, comment-free, whitespace-collapsed list of CREATE statements
 * (history tables excluded). Normalization makes the Flyway-vs-runner comparison schema-semantic —
 * it catches column/type/constraint/trigger/FTS differences but ignores how each tool chunked the
 * submitted SQL (leading comments, indentation), which SQLite stores verbatim and which differ
 * cosmetically between submitters.
 */
internal fun dumpSchema(ds: DataSource): String =
    ds.connection.use { c ->
        c.createStatement().use { s ->
            s
                .executeQuery(
                    "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL " +
                        "AND name NOT LIKE 'flyway_%' AND name NOT LIKE 'sqlite_%' " +
                        "AND name != 'schema_migrations' ORDER BY type, name",
                ).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }
                        .map(::normalizeSchemaStatement)
                        .joinToString("\n")
                }
        }
    }

private fun normalizeSchemaStatement(sql: String): String =
    sql
        .replace(Regex("--[^\\n]*"), " ")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

class MigrationRunnerTest :
    FunSpec({

        val m1 = Migration(1, "first", "ck1", "CREATE TABLE a (x INTEGER);")
        val m2 = Migration(2, "second", "ck2", "CREATE TABLE b (y INTEGER);")

        test("a fresh database applies all migrations and reports the latest version") {
            val (path, ds) = freshDb()
            val version = MigrationRunner(path, listOf(m1, m2)).migrate()
            version shouldBe "2"
            ds.tableExists("a") shouldBe true
            ds.tableExists("b") shouldBe true
        }

        test("a second run is a no-op and applies only newly-added migrations") {
            val (path, ds) = freshDb()
            MigrationRunner(path, listOf(m1)).migrate() shouldBe "1"
            ds.tableExists("b") shouldBe false
            MigrationRunner(path, listOf(m1, m2)).migrate() shouldBe "2"
            ds.tableExists("b") shouldBe true
        }

        test("migrate(upTo) applies only migrations at or below the target version") {
            val (path, ds) = freshDb()
            MigrationRunner(path, listOf(m1, m2)).migrate(upTo = 1) shouldBe "1"
            ds.tableExists("b") shouldBe false
        }

        test("an edited (checksum-changed) applied migration fails loudly") {
            val (path, _) = freshDb()
            MigrationRunner(path, listOf(m1)).migrate()
            val tampered = m1.copy(checksum = "DIFFERENT")
            shouldThrow<IllegalStateException> {
                MigrationRunner(path, listOf(tampered)).migrate()
            }
        }

        test("currentSchemaVersion is null on an unmigrated database") {
            val (path, _) = freshDb()
            MigrationRunner(path, emptyList()).currentSchemaVersion() shouldBe null
        }

        test("V50 anchors the book natural-key index to (folder_id, root_rel_path)") {
            val (path, ds) = freshDb()
            MigrationRunner(path).migrate()
            val indexSql =
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s
                            .executeQuery(
                                "SELECT sql FROM sqlite_master WHERE type='index' AND name='idx_book_natural_key'",
                            ).use { if (it.next()) it.getString(1) else null }
                    }
                }
            indexSql shouldBe "CREATE UNIQUE INDEX idx_book_natural_key ON books(folder_id, root_rel_path)"
        }

        test("V51 backfills initial_scan_completed_at for libraries that already hold a live book") {
            val (path, ds) = freshDb()
            // Migrate to V50 (pre-V51) so the backfill sees the rows we seed below.
            MigrationRunner(path).migrate(upTo = 50)
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    // populated: has a live book → should backfill; empty: no books → stays null;
                    // tombstoned-book-only: its only book is soft-deleted → stays null.
                    s.executeUpdate(
                        "INSERT INTO libraries (id, name, created_at, updated_at) VALUES " +
                            "('populated', 'Populated', 100, 4242), ('empty', 'Empty', 100, 4242), " +
                            "('deleted-books', 'DeletedBooks', 100, 4242)",
                    )
                    s.executeUpdate(
                        "INSERT INTO books (id, library_id, title, total_duration, root_rel_path, scanned_at, " +
                            "revision, created_at, updated_at, deleted_at) VALUES " +
                            "('b1', 'populated', 'Book', 0, 'Book', 0, 0, 0, 0, NULL), " +
                            "('b2', 'deleted-books', 'Gone', 0, 'Gone', 0, 0, 0, 0, 999)",
                    )
                }
            }

            // Apply V51 (adds the column + runs the backfill).
            MigrationRunner(path).migrate()

            fun stampOf(id: String): Long? =
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s
                            .executeQuery("SELECT initial_scan_completed_at FROM libraries WHERE id = '$id'")
                            .use { if (it.next()) (it.getObject(1) as Number?)?.toLong() else null }
                    }
                }

            stampOf("populated") shouldBe 4242L // backfilled from updated_at
            stampOf("empty") shouldBe null // no live book → never scanned
            stampOf("deleted-books") shouldBe null // only a tombstoned book → not counted
        }

        test("the runner reproduces the Flyway golden schema exactly (all migrations)") {
            val (path, ds) = freshDb()
            MigrationRunner(path).migrate() shouldBe MigrationCatalog.all.maxOf { it.version }.toString()
            val golden =
                checkNotNull(this::class.java.getResource("/golden/schema-current.txt")).readText().trim()
            dumpSchema(ds).trim() shouldBe golden
        }
    })
