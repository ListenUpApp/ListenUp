package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.execute(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.count(sql: String): Int =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun receipt(
    id: String,
    entity: String,
    undoneAt: String = "NULL",
) = "INSERT INTO match_receipts (id, entity_kind, entity_id, applied_by, applied_at, revision_after, snapshot, changes, " +
    "undone_at) VALUES ('$id', 'book', '$entity', 'u1', 1, 5, '{}', '[]', $undoneAt)"

/** V90: one live match receipt per entity; undone receipts don't count against it. */
class V90MigrationTest :
    FunSpec({
        test("an entity has at most one live receipt, and any number of undone ones") {
            val path =
                Files
                    .createTempFile("listenup-v90-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate()

            ds.execute(receipt("r1", "b1"))
            shouldThrowAny { ds.execute(receipt("r2", "b1")) }
            ds.execute(receipt("r3", "b1", undoneAt = "7"))
            ds.execute(receipt("r4", "b1", undoneAt = "8"))
            ds.execute(receipt("r5", "b2"))

            ds.count("SELECT COUNT(*) FROM match_receipts WHERE undone_at IS NULL") shouldBe 2
            ds.count("SELECT COUNT(*) FROM match_receipts") shouldBe 4
        }
    })
