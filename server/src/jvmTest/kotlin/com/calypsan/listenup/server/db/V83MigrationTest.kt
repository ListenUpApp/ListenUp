package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.SQLException
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

/** A database migrated up to just before V83, as a server running main today holds it, with one user and two books. */
private fun databaseBeforeV83(): Pair<String, DataSource> {
    val path =
        Files
            .createTempFile("listenup-v83-", ".db")
            .toFile()
            .apply { deleteOnExit() }
            .absolutePath
    val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
    MigrationRunner(path).migrate(upTo = 82)
    ds.execute(
        "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
            "VALUES ('u1', 'u1@x', 'u1@x', 'phc', 'MEMBER', 'u1', 'ACTIVE', 0, 0)",
    )
    ds.execute("INSERT INTO libraries (id, name, created_at, updated_at) VALUES ('lib', 'Library', 0, 0)")
    listOf("book1", "book2").forEach { book ->
        ds.execute(
            "INSERT INTO books (id, library_id, title, total_duration, root_rel_path, scanned_at, revision, created_at, updated_at) " +
                "VALUES ('$book', 'lib', 'Book', 0, '$book', 0, 1, 0, 0)",
        )
    }
    return path to ds
}

/**
 * V83 brings the books a listener keeps off Hardcover (#1541): one row per user and book, when it was kept
 * off, gone with its user — and with its book, on the hard delete that is the only kind that removes a row.
 */
class V83MigrationTest :
    FunSpec({
        test("V83 keeps one row per user and book, gone with its book or its user") {
            val (path, ds) = databaseBeforeV83()

            MigrationRunner(path).migrate()

            ds.execute("INSERT INTO hardcover_book_exclusions (user_id, book_id, excluded_at) VALUES ('u1', 'book1', 5)")
            ds.execute("INSERT INTO hardcover_book_exclusions (user_id, book_id, excluded_at) VALUES ('u1', 'book2', 6)")
            shouldThrow<SQLException> {
                ds.execute("INSERT INTO hardcover_book_exclusions (user_id, book_id, excluded_at) VALUES ('u1', 'book1', 7)")
            }
            ds.single("SELECT excluded_at FROM hardcover_book_exclusions WHERE book_id = 'book1'") shouldBe 5

            ds.execute("DELETE FROM books WHERE id = 'book2'")
            ds.single("SELECT COUNT(*) FROM hardcover_book_exclusions") shouldBe 1

            ds.execute("DELETE FROM users WHERE id = 'u1'")
            ds.single("SELECT COUNT(*) FROM hardcover_book_exclusions") shouldBe 0
        }
    })
