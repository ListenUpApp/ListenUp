package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.run(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.column(
    sql: String,
    index: Int = 1,
): List<String> =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(index)) } }
        }
    }

/** The `detail` column of an `EXPLAIN QUERY PLAN` row. */
private const val PLAN_DETAIL = 4

/**
 * V101 indexes `listening_events` on `ended_at`, so Discover's recent listens — every OTHER user's listens since a
 * cut-off — read a range instead of the whole table. Every existing index leads with `user_id`, which the query's
 * `user_id != ?` cannot use.
 */
class V101MigrationTest :
    FunSpec({
        test("V101 indexes listening_events by ended_at, and the recent-listens query uses it instead of a scan") {
            val path =
                Files
                    .createTempFile("listenup-v101-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 100)
            ds.run(
                "INSERT INTO listening_events (id, user_id, book_id, start_position_ms, end_position_ms, started_at, " +
                    "ended_at, playback_speed, tz, created_at, updated_at) VALUES ('e1', 'u1', 'b1', 0, 1, 1, 2, 1.0, " +
                    "'UTC', 1, 1)",
            )

            MigrationRunner(path).migrate()

            ds.column("SELECT id FROM listening_events") shouldBe listOf("e1")
            // The SQL of selectUnfinishedListensExcluding (ListeningEvents.sq), with literals for its two parameters.
            val plan =
                ds
                    .column(
                        "EXPLAIN QUERY PLAN SELECT e.user_id, e.book_id, e.started_at, e.ended_at FROM listening_events e " +
                            "WHERE e.user_id != 'viewer' AND e.deleted_at IS NULL AND e.ended_at >= 0 AND NOT EXISTS (" +
                            "SELECT 1 FROM playback_positions p WHERE p.user_id = e.user_id AND p.book_id = e.book_id " +
                            "AND p.finished = 1 AND p.deleted_at IS NULL)",
                        PLAN_DETAIL,
                    ).joinToString(" | ")
            plan shouldContain "USING INDEX idx_listening_events_ended_at"
            plan shouldNotContain "SCAN e"
        }
    })
