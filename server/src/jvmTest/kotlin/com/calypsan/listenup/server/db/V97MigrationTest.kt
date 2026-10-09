package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.execute(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.long(sql: String): Long =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

private const val COLS = "id, type, text, created_at, updated_at, revision"

/** V97: a world event has exactly one home, and an anchor that is a book and a non-negative moment together. */
class V97MigrationTest :
    FunSpec({
        fun migrated(): DataSource {
            val path =
                Files
                    .createTempFile("listenup-v97-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            MigrationRunner(path).migrate()
            return fileBackedTestDataSource("jdbc:sqlite:$path").also {
                it.execute(
                    "INSERT INTO book_series (id, name, normalized_name, revision, created_at, updated_at) " +
                        "VALUES ('s1', 'S', 's', 1, 1, 1)",
                )
            }
        }

        test("a world event with no home, or with both homes, is refused") {
            val ds = migrated()
            shouldThrowAny {
                ds.execute("INSERT INTO world_events ($COLS) VALUES ('w1', 'note', 'x', 1, 1, 1)")
            }.message shouldContain "CHECK constraint"
            shouldThrowAny {
                ds.execute(
                    "INSERT INTO world_events ($COLS, home_series_id, home_book_id) " +
                        "VALUES ('w2', 'note', 'x', 1, 1, 1, 's1', 'b1')",
                )
            }.message shouldContain "CHECK constraint"
            ds.execute("INSERT INTO world_events ($COLS, home_series_id) VALUES ('w3', 'note', 'x', 1, 1, 1, 's1')")
            ds.long("SELECT COUNT(*) FROM world_events") shouldBe 1
        }

        test("an anchor is a book and a moment together, and never negative") {
            val ds = migrated()
            shouldThrowAny {
                ds.execute(
                    "INSERT INTO world_events ($COLS, home_series_id, position_ms) " +
                        "VALUES ('w1', 'note', 'x', 1, 1, 1, 's1', 5)",
                )
            }.message shouldContain "CHECK constraint"
            shouldThrowAny {
                ds.execute(
                    "INSERT INTO world_events ($COLS, home_series_id, book_id, position_ms) " +
                        "VALUES ('w2', 'note', 'x', 1, 1, 1, 's1', 'b1', -1)",
                )
            }.message shouldContain "CHECK constraint"
        }

        test("mentions and the merge snapshot have their tables") {
            val ds = migrated()
            ds.long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'world_event_mentions'") shouldBe 1
            ds.long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'series_merge_receipt_world_events'") shouldBe 1
        }
    })
