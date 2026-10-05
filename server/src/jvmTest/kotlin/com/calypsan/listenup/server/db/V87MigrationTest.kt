package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.execute(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.single(sql: String): Any? =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                rs.next()
                rs.getObject(1)
            }
        }
    }

private fun DataSource.book(
    id: String,
    asin: String?,
) = execute(
    "INSERT INTO books (id, library_id, title, total_duration, root_rel_path, scanned_at, revision, created_at, updated_at, asin) " +
        "VALUES ('$id', 'lib', '$id', 0, '$id', 0, 1, 0, 0, ${asin?.let { "'$it'" } ?: "NULL"})",
)

/**
 * V87 adds `external_refs`, the provider-neutral identity matching speaks. Every book that already has
 * an ASIN gets its `audible` ref, so the day this ships, every matched book is still matched.
 */
class V87MigrationTest :
    FunSpec({
        test("V87 gives every book with an ASIN its audible ref, and nothing to a book without one") {
            val path =
                Files
                    .createTempFile("listenup-v87-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 86)
            ds.execute("INSERT INTO libraries (id, name, created_at, updated_at) VALUES ('lib', 'Library', 0, 0)")
            ds.book("matched", asin = "B08G9PRS1K")
            ds.book("blank", asin = "  ")
            ds.book("unmatched", asin = null)

            MigrationRunner(path).migrate()

            ds.single("SELECT COUNT(*) FROM external_refs") shouldBe 1
            ds.single(
                "SELECT external_id FROM external_refs " +
                    "WHERE entity_kind = 'book' AND entity_id = 'matched' AND provider = 'audible'",
            ) shouldBe "B08G9PRS1K"
            ds.single("SELECT region FROM external_refs WHERE entity_id = 'matched'") shouldBe null
        }

        test("a book holds one ref per provider") {
            val path =
                Files
                    .createTempFile("listenup-v87-pk-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate()
            ds.execute(
                "INSERT INTO external_refs (entity_kind, entity_id, provider, external_id) VALUES ('book', 'b1', 'hardcover', '428')",
            )
            shouldThrowAny {
                ds.execute(
                    "INSERT INTO external_refs (entity_kind, entity_id, provider, external_id) VALUES ('book', 'b1', 'hardcover', '999')",
                )
            }
        }
    })
