package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
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

/**
 * V86 adds `book_reads.started_at`: the day the reader said they started a read. Every read that
 * existed before has no picked start, so it keeps deriving its start from listening, as it did.
 */
class V86MigrationTest :
    FunSpec({
        test("V86 keeps every existing read, with no picked start, and takes one on a new read") {
            val path =
                Files
                    .createTempFile("listenup-v86-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 85)
            ds.execute(
                "INSERT INTO book_reads (id, user_id, book_id, finished_at, source, created_at) " +
                    "VALUES ('r1', 'u1', 'b1', 100, 'playback', 100)",
            )

            MigrationRunner(path).migrate()

            ds.single("SELECT finished_at FROM book_reads WHERE id = 'r1'") shouldBe 100
            ds.single("SELECT started_at FROM book_reads WHERE id = 'r1'") shouldBe null
            ds.execute(
                "INSERT INTO book_reads (id, user_id, book_id, finished_at, source, created_at, started_at) " +
                    "VALUES ('r2', 'u1', 'b1', 300, 'playback', 300, 200)",
            )
            ds.single("SELECT started_at FROM book_reads WHERE id = 'r2'") shouldBe 200
        }
    })
