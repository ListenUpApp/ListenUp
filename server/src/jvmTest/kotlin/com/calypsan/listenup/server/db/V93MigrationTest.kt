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

/** V93: entities have exactly one home; the Story World permissions default contribute-on, curate-off. */
class V93MigrationTest :
    FunSpec({
        fun migrated(): DataSource {
            val path =
                Files
                    .createTempFile("listenup-v93-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            MigrationRunner(path).migrate()
            return fileBackedTestDataSource("jdbc:sqlite:$path")
        }

        test("an entity with no home, or with both homes, is refused") {
            val ds = migrated()
            val cols = "id, kind, name, created_at, updated_at, revision"
            shouldThrowAny {
                ds.execute("INSERT INTO entities ($cols) VALUES ('e1', 'character', 'A', 1, 1, 1)")
            }.message shouldContain "CHECK constraint"
            ds.execute(
                "INSERT INTO book_series (id, name, normalized_name, revision, created_at, updated_at) " +
                    "VALUES ('s1', 'S', 's', 1, 1, 1)",
            )
            shouldThrowAny {
                ds.execute(
                    "INSERT INTO entities ($cols, home_series_id, home_book_id) " +
                        "VALUES ('e3', 'character', 'C', 1, 1, 1, 's1', 'b1')",
                )
            }.message shouldContain "CHECK constraint"
            ds.execute("INSERT INTO entities ($cols, home_series_id) VALUES ('e2', 'character', 'B', 1, 1, 1, 's1')")
            ds.long("SELECT COUNT(*) FROM entities") shouldBe 1
        }

        test("existing and new users default to contribute on, curate off") {
            val ds = migrated()
            ds.execute(
                "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at) VALUES ('u1', 'a@b.c', 'a@b.c', 'x', 'MEMBER', 'A', 'ACTIVE', 1, 1)",
            )
            ds.long("SELECT can_contribute_story_world FROM users WHERE id = 'u1'") shouldBe 1
            ds.long("SELECT can_curate_story_world FROM users WHERE id = 'u1'") shouldBe 0
        }

        test("history rows and merge snapshots have their tables") {
            val ds = migrated()
            ds.long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'story_world_history'") shouldBe 1
            ds.long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'series_merge_receipt_entities'") shouldBe 1
        }
    })
