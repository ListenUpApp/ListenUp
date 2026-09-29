package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.execute(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.attempts(): List<Triple<String, String, Long>> =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT book_id, source, attempted_at FROM external_rating_attempts ORDER BY book_id, source").use { rs ->
                generateSequence { if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getLong(3)) else null }.toList()
            }
        }
    }

/**
 * V75 re-keys `external_rating_attempts` by (book, source). Before it, an attempt row meant "Audible
 * tried this book" — the only source PR 2 ran — so a book already tried there must keep that memory
 * as an AUDIBLE attempt while becoming a candidate for every source that has never tried it.
 */
class V75MigrationTest :
    FunSpec({
        test("an attempt recorded before V75 becomes an AUDIBLE attempt, and a book can hold one per source") {
            val path =
                Files.createTempFile("listenup-v75-", ".db").toFile().apply { deleteOnExit() }.absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 74)
            ds.execute("INSERT INTO libraries (id, name, created_at, updated_at) VALUES ('lib', 'Library', 0, 0)")
            ds.execute(
                "INSERT INTO books (id, library_id, title, total_duration, root_rel_path, scanned_at, revision, created_at, updated_at) " +
                    "VALUES ('book1', 'lib', 'Book', 0, 'book1', 0, 1, 0, 0)",
            )
            ds.execute("INSERT INTO external_rating_attempts (book_id, attempted_at) VALUES ('book1', 1234)")

            MigrationRunner(path).migrate()

            ds.attempts() shouldBe listOf(Triple("book1", "AUDIBLE", 1234L))
            ds.execute("INSERT INTO external_rating_attempts (book_id, source, attempted_at) VALUES ('book1', 'GOODREADS', 5678)")
            ds.attempts() shouldBe
                listOf(Triple("book1", "AUDIBLE", 1234L), Triple("book1", "GOODREADS", 5678L))
        }
    })
