package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.run(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.rows(sql: String): List<List<Any?>> =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) })
                }
            }
        }
    }

/** V100 gives every playback position a place for its finish-card dismissal, empty, keeping every row. */
class V100MigrationTest :
    FunSpec({
        test("V100 adds an empty finished_card_dismissed_at to playback_positions, keeping every row") {
            val path =
                Files
                    .createTempFile("listenup-v100-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 99)
            ds.run(
                "INSERT INTO playback_positions (id, user_id, book_id, position_ms, last_played_at, finished, finished_at, " +
                    "created_at, updated_at) VALUES ('p1', 'u1', 'b1', 9000, 10, 1, 9, 1, 1)",
            )

            MigrationRunner(path).migrate()

            ds.rows(
                "SELECT book_id, typeof(finished_at), typeof(finished_card_dismissed_at) FROM playback_positions",
            ) shouldBe listOf(listOf("b1", "integer", "null"))
        }
    })
