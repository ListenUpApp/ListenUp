package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.execute(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.count(sql: String): Long =
    connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }

private fun DataSource.pushColumns(): Pair<Long?, String?> =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT last_synced_at, push_error FROM hardcover_connections WHERE user_id = 'u1'").use { rs ->
                rs.next()
                (rs.getObject(1) as Number?)?.toLong() to rs.getString(2)
            }
        }
    }

/**
 * V76 adds Hardcover push: an existing connection survives with its two new columns empty, and the
 * three new tables follow their owners — links and outbox rows go with the book or the user, while a
 * pushed-read id outlives the book (B3's echo suppression depends on it) and goes only with the user.
 */
class V76MigrationTest :
    FunSpec({
        test("V76 keeps a connection, adds empty push columns, and each new table cascades the way it should") {
            val path =
                Files
                    .createTempFile("listenup-v76-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 75)
            ds.execute(
                "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
                    "VALUES ('u1', 'u1@x', 'u1@x', 'phc', 'MEMBER', 'u1', 'ACTIVE', 0, 0)",
            )
            ds.execute(
                "INSERT INTO hardcover_connections (user_id, hc_user_id, hc_username, access_token_enc, access_expires_at, " +
                    "refresh_token_enc, refresh_expires_at, scopes, connected_at) VALUES ('u1', 42, 'simon', 'a', 1, 'r', 2, 's', 3)",
            )
            ds.execute("INSERT INTO libraries (id, name, created_at, updated_at) VALUES ('lib', 'Library', 0, 0)")
            ds.execute(
                "INSERT INTO books (id, library_id, title, total_duration, root_rel_path, scanned_at, revision, created_at, updated_at) " +
                    "VALUES ('book1', 'lib', 'Book', 0, 'book1', 0, 1, 0, 0)",
            )

            MigrationRunner(path).migrate()

            ds.pushColumns() shouldBe (null to null)
            ds.execute(
                "INSERT INTO hardcover_book_links (user_id, book_id, hc_book_id, hc_edition_id, match_method, match_state, updated_at) " +
                    "VALUES ('u1', 'book1', 427578, 9001, 'ASIN', 'LINKED', 0)",
            )
            ds.execute(
                "INSERT INTO hardcover_outbox (user_id, book_id, listen_through_started_at, op, payload, created_at, next_attempt_at) " +
                    "VALUES ('u1', 'book1', 100, 'START', '{}', 0, 0)",
            )
            ds.execute("INSERT INTO hardcover_pushed_reads (user_id, hc_read_id, book_id, recorded_at) VALUES ('u1', 7, 'book1', 0)")

            ds.execute("DELETE FROM books WHERE id = 'book1'")
            ds.count("SELECT COUNT(*) FROM hardcover_book_links") shouldBe 0L
            ds.count("SELECT COUNT(*) FROM hardcover_outbox") shouldBe 0L
            ds.count("SELECT COUNT(*) FROM hardcover_pushed_reads") shouldBe 1L

            ds.execute("DELETE FROM users WHERE id = 'u1'")
            ds.count("SELECT COUNT(*) FROM hardcover_pushed_reads") shouldBe 0L
            ds.count("SELECT COUNT(*) FROM hardcover_connections") shouldBe 0L
        }
    })
