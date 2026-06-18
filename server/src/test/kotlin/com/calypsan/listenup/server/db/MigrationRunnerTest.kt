package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun freshDataSource(): HikariDataSource {
    val tmp = Files.createTempFile("listenup-runner-test-", ".db").toFile().apply { deleteOnExit() }
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"
            maximumPoolSize = 1
            isAutoCommit = false
            addDataSourceProperty("foreign_keys", "true")
            validate()
        },
    )
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
            s.executeQuery(
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
            val ds = freshDataSource()
            val version = MigrationRunner(ds, listOf(m1, m2)).migrate()
            version shouldBe "2"
            ds.tableExists("a") shouldBe true
            ds.tableExists("b") shouldBe true
        }

        test("a second run is a no-op and applies only newly-added migrations") {
            val ds = freshDataSource()
            MigrationRunner(ds, listOf(m1)).migrate() shouldBe "1"
            ds.tableExists("b") shouldBe false
            MigrationRunner(ds, listOf(m1, m2)).migrate() shouldBe "2"
            ds.tableExists("b") shouldBe true
        }

        test("migrate(upTo) applies only migrations at or below the target version") {
            val ds = freshDataSource()
            MigrationRunner(ds, listOf(m1, m2)).migrate(upTo = 1) shouldBe "1"
            ds.tableExists("b") shouldBe false
        }

        test("an edited (checksum-changed) applied migration fails loudly") {
            val ds = freshDataSource()
            MigrationRunner(ds, listOf(m1)).migrate()
            val tampered = m1.copy(checksum = "DIFFERENT")
            shouldThrow<IllegalStateException> {
                MigrationRunner(ds, listOf(tampered)).migrate()
            }
        }

        test("currentSchemaVersion is null on an unmigrated database") {
            val ds = freshDataSource()
            MigrationRunner(ds, emptyList()).currentSchemaVersion() shouldBe null
        }

        test("the runner reproduces the Flyway golden schema exactly (all migrations)") {
            val ds = freshDataSource()
            MigrationRunner(ds).migrate() shouldBe MigrationCatalog.all.maxOf { it.version }.toString()
            val golden =
                checkNotNull(this::class.java.getResource("/golden/schema-current.txt")).readText().trim()
            dumpSchema(ds).trim() shouldBe golden
        }
    })
