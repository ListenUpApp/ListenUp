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

private fun DataSource.user(id: String) =
    execute(
        "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
            "VALUES ('$id', '$id@x', '$id@x', 'phc', 'MEMBER', '$id', 'ACTIVE', 0, 0)",
    )

private fun DataSource.shelf(
    id: String,
    userId: String,
    name: String,
    createdAt: Long,
    deletedAt: Long? = null,
) = execute(
    "INSERT INTO shelves (id, user_id, name, description, is_private, created_at, updated_at, revision, deleted_at) " +
        "VALUES ('$id', '$userId', '$name', '', 0, $createdAt, $createdAt, 1, ${deletedAt ?: "NULL"})",
)

/** A database migrated up to just before V80, as a server running main today holds it. */
private fun databaseBeforeV80(): Pair<String, DataSource> {
    val path =
        Files
            .createTempFile("listenup-v80-", ".db")
            .toFile()
            .apply { deleteOnExit() }
            .absolutePath
    val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
    MigrationRunner(path).migrate(upTo = 79)
    return path to ds
}

/**
 * V80 brings Hardcover's Want to Read in (#1539). Each user's starter shelf is remembered: the earliest
 * live shelf they own named exactly "To Read", or nothing. Hardcover's shelf records are one per user and
 * book, in one of two states. Every connection's next pull reads the whole shelf, so Want to Read arrives
 * at once rather than at the next daily full pull.
 */
class V80MigrationTest :
    FunSpec({
        test("V80 remembers each user's earliest live To Read shelf, or nothing, and makes the next pull a full one") {
            val (path, ds) = databaseBeforeV80()
            listOf("u1", "u2", "u3").forEach { ds.user(it) }
            // u1: an older To Read they deleted, an older shelf with another name, then two live To Reads.
            ds.shelf("s-deleted", "u1", "To Read", createdAt = 50, deletedAt = 60)
            ds.shelf("s-favourites", "u1", "Favourites", createdAt = 10)
            ds.shelf("s-second", "u1", "To Read", createdAt = 200)
            ds.shelf("s-first", "u1", "To Read", createdAt = 100)
            // u2 renamed theirs before V80: nothing is called To Read any more.
            ds.shelf("s-renamed", "u2", "Someday", createdAt = 100)
            // u3 has no shelves at all.
            ds.execute(
                "INSERT INTO hardcover_connections (user_id, hc_user_id, hc_username, access_token_enc, access_expires_at, " +
                    "refresh_token_enc, refresh_expires_at, scopes, connected_at, last_full_pull_at) " +
                    "VALUES ('u1', 42, 'simon', 'a', 1, 'r', 2, 's', 3, 5)",
            )

            MigrationRunner(path).migrate()

            ds.single("SELECT starter_shelf_id FROM users WHERE id = 'u1'") shouldBe "s-first"
            ds.single("SELECT starter_shelf_id FROM users WHERE id = 'u2'") shouldBe null
            ds.single("SELECT starter_shelf_id FROM users WHERE id = 'u3'") shouldBe null
            ds.single("SELECT hardcover_shelf_id FROM users WHERE id = 'u1'") shouldBe null
            ds.single("SELECT last_full_pull_at FROM hardcover_connections WHERE user_id = 'u1'") shouldBe null
        }

        test("a Hardcover shelf record is one per user and book, ON_SHELF or USER_REMOVED") {
            val (path, ds) = databaseBeforeV80()
            ds.user("u1")
            ds.execute("INSERT INTO libraries (id, name, created_at, updated_at) VALUES ('lib', 'Library', 0, 0)")
            ds.execute(
                "INSERT INTO books (id, library_id, title, total_duration, root_rel_path, scanned_at, revision, created_at, updated_at) " +
                    "VALUES ('book1', 'lib', 'Book', 0, 'book1', 0, 1, 0, 0)",
            )
            ds.shelf("s1", "u1", "To Read", createdAt = 100)

            MigrationRunner(path).migrate()

            ds.execute(
                "INSERT INTO hardcover_shelf_entries (user_id, book_id, shelf_id, hc_user_book_id, state, seen_at, updated_at) " +
                    "VALUES ('u1', 'book1', 's1', 7, 'ON_SHELF', 1, 1)",
            )
            shouldThrowAny {
                ds.execute(
                    "INSERT INTO hardcover_shelf_entries (user_id, book_id, shelf_id, hc_user_book_id, state, seen_at, updated_at) " +
                        "VALUES ('u1', 'book1', 's1', 8, 'ON_SHELF', 1, 1)",
                )
            }
            shouldThrowAny { ds.execute("UPDATE hardcover_shelf_entries SET state = 'GONE'") }
            ds.single("SELECT COUNT(*) FROM hardcover_shelf_entries") shouldBe 1
        }
    })
