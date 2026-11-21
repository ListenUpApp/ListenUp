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

/** V102 adds an empty `sittings` table and touches nothing it is built from. */
class V102MigrationTest :
    FunSpec({
        test("V102 creates an empty sittings table and leaves the listening it is built from alone") {
            val path =
                Files
                    .createTempFile("listenup-v102-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 100)
            ds.run(
                "INSERT INTO listening_events (id, user_id, book_id, start_position_ms, end_position_ms, started_at, " +
                    "ended_at, playback_speed, tz, revision, created_at, updated_at) " +
                    "VALUES ('e1', 'u1', 'b1', 0, 60000, 0, 60000, 1.0, 'UTC', 1, 1, 1)",
            )

            MigrationRunner(path).migrate()

            ds.rows("SELECT COUNT(*) FROM sittings") shouldBe listOf(listOf(0))
            ds.rows("SELECT id FROM listening_events") shouldBe listOf(listOf("e1"))
        }

        test("a sitting inserted with only what the reconciler always writes defaults to no start and no finish") {
            val path =
                Files
                    .createTempFile("listenup-v102-defaults-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate()

            ds.run(
                "INSERT INTO sittings (id, user_id, book_id, started_at, ended_at, listened_ms, created_at, updated_at, " +
                    "revision) VALUES ('s1', 'u1', 'b1', 0, 60000, 60000, 1, 1, 1)",
            )

            ds.rows("SELECT began_read, finished_read, deleted_at FROM sittings") shouldBe
                listOf(listOf("none", 0, null))
        }
    })
