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

/**
 * V77 adds the Hardcover pull (#601 B3): existing reads survive with no Hardcover read id, a Hardcover
 * read id is unique per user (so pulling twice can't duplicate), ListenUp's own id-less reads are not
 * constrained by it, and a connection and a link each gain empty pull columns.
 */
class V77MigrationTest :
    FunSpec({
        test("V77 keeps every read, makes hc_read_id unique per user, and adds empty pull columns") {
            val path =
                Files
                    .createTempFile("listenup-v77-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 76)
            ds.execute(
                "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
                    "VALUES ('u1', 'u1@x', 'u1@x', 'phc', 'MEMBER', 'u1', 'ACTIVE', 0, 0)",
            )
            ds.execute("INSERT INTO libraries (id, name, created_at, updated_at) VALUES ('lib', 'Library', 0, 0)")
            ds.execute(
                "INSERT INTO books (id, library_id, title, total_duration, root_rel_path, scanned_at, revision, created_at, updated_at) " +
                    "VALUES ('book1', 'lib', 'Book', 0, 'book1', 0, 1, 0, 0)",
            )
            ds.execute(
                "INSERT INTO book_reads (id, user_id, book_id, finished_at, source, created_at) " +
                    "VALUES ('r1', 'u1', 'book1', 100, 'playback', 100)",
            )
            ds.execute(
                "INSERT INTO hardcover_connections (user_id, hc_user_id, hc_username, access_token_enc, access_expires_at, " +
                    "refresh_token_enc, refresh_expires_at, scopes, connected_at) VALUES ('u1', 42, 'simon', 'a', 1, 'r', 2, 's', 3)",
            )
            ds.execute(
                "INSERT INTO hardcover_book_links (user_id, book_id, hc_book_id, match_state, updated_at) " +
                    "VALUES ('u1', 'book1', 427578, 'LINKED', 0)",
            )

            MigrationRunner(path).migrate()

            ds.single("SELECT hc_read_id FROM book_reads WHERE id = 'r1'") shouldBe null
            ds.single("SELECT pull_cursor FROM hardcover_connections WHERE user_id = 'u1'") shouldBe null
            ds.single("SELECT pull_error FROM hardcover_connections WHERE user_id = 'u1'") shouldBe null
            ds.single("SELECT last_full_pull_at FROM hardcover_connections WHERE user_id = 'u1'") shouldBe null
            ds.single("SELECT pull_seen_at FROM hardcover_book_links WHERE user_id = 'u1'") shouldBe null

            // ListenUp's own reads carry no Hardcover id; any number of them may coexist.
            ds.execute(
                "INSERT INTO book_reads (id, user_id, book_id, finished_at, source, created_at) " +
                    "VALUES ('r2', 'u1', 'book1', 200, 'playback', 200)",
            )
            // A Hardcover read id appears at most once per user.
            ds.execute(
                "INSERT INTO book_reads (id, user_id, book_id, finished_at, source, created_at, hc_read_id) " +
                    "VALUES ('h1', 'u1', 'book1', 300, 'hardcover', 300, 6980588)",
            )
            shouldThrowAny {
                ds.execute(
                    "INSERT INTO book_reads (id, user_id, book_id, finished_at, source, created_at, hc_read_id) " +
                        "VALUES ('h2', 'u1', 'book1', 400, 'hardcover', 400, 6980588)",
                )
            }
            ds.single("SELECT COUNT(*) FROM book_reads") shouldBe 3
        }
    })
