package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
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

private fun DataSource.connection(
    userId: String,
    connectedAt: Long,
) = execute(
    "INSERT INTO hardcover_connections (user_id, hc_user_id, hc_username, access_token_enc, access_expires_at, " +
        "refresh_token_enc, refresh_expires_at, scopes, connected_at) " +
        "VALUES ('$userId', 42, '$userId', 'a', 1, 'r', 2, 's', $connectedAt)",
)

private fun DataSource.read(
    id: String,
    userId: String,
    bookId: String,
    finishedAt: Long,
    source: String = "playback",
) = execute(
    "INSERT INTO book_reads (id, user_id, book_id, finished_at, source, created_at) " +
        "VALUES ('$id', '$userId', '$bookId', $finishedAt, '$source', $finishedAt)",
)

/** A database migrated up to just before V81, as a server running main today holds it. */
private fun databaseBeforeV81(): Pair<String, DataSource> {
    val path =
        Files
            .createTempFile("listenup-v81-", ".db")
            .toFile()
            .apply { deleteOnExit() }
            .absolutePath
    val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
    MigrationRunner(path).migrate(upTo = 80)
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
 * V81 brings the Hardcover history backfill (#1540): the offer's state per user, the ledger of reads sent,
 * and the read a HISTORY outbox row carries. A user already connected with ListenUp reads from before
 * connecting is offered them once; nobody else is.
 */
class V81MigrationTest :
    FunSpec({
        test("V81 offers history once to each existing connection that has own reads from before it connected") {
            val (path, ds) = databaseBeforeV81()
            listOf("u1", "u2", "u3", "u4").forEach { ds.user(it) }
            // u1: connected at 1000 with a read finished at 500 — history.
            ds.connection("u1", connectedAt = 1_000)
            ds.read("r1", "u1", "book1", finishedAt = 500)
            // u2: connected at 1000, but its only read finished after that — pushed live, not history.
            ds.connection("u2", connectedAt = 1_000)
            ds.read("r2", "u2", "book1", finishedAt = 2_000)
            // u3: connected, with only a read pulled from Hardcover — never history.
            ds.connection("u3", connectedAt = 1_000)
            ds.read("r3", "u3", "book1", finishedAt = 500, source = "hardcover")
            // u4: own history, but not connected.
            ds.read("r4", "u4", "book1", finishedAt = 500)

            MigrationRunner(path).migrate()

            ds.single("SELECT state FROM hardcover_history WHERE user_id = 'u1'") shouldBe "OFFERED"
            ds.single("SELECT hc_user_id FROM hardcover_history WHERE user_id = 'u1'") shouldBe 42
            ds.single("SELECT COUNT(*) FROM hardcover_history") shouldBe 1
        }

        test("the ledger goes with the read it records, the offer with its user, and an outbox row can carry a read") {
            val (path, ds) = databaseBeforeV81()
            ds.user("u1")
            ds.read("r1", "u1", "book1", finishedAt = 500)
            ds.read("r2", "u1", "book2", finishedAt = 600)

            MigrationRunner(path).migrate()

            ds.execute(
                "INSERT INTO hardcover_history (user_id, hc_user_id, state, total_books, updated_at) " +
                    "VALUES ('u1', 42, 'SENDING', 2, 1)",
            )
            ds.execute("INSERT INTO hardcover_history_reads (user_id, read_id, outcome, recorded_at) VALUES ('u1', 'r1', 'SENT', 1)")
            ds.execute(
                "INSERT INTO hardcover_history_reads (user_id, read_id, outcome, recorded_at) " +
                    "VALUES ('u1', 'r2', 'ALREADY_THERE', 1)",
            )
            ds.execute(
                "INSERT INTO hardcover_outbox (user_id, book_id, listen_through_started_at, op, payload, created_at, " +
                    "next_attempt_at, history_read_id) " +
                    "VALUES ('u1', 'book2', 600, 'HISTORY', '{}', 1, 1, 'r2')",
            )
            ds.single("SELECT history_read_id FROM hardcover_outbox WHERE user_id = 'u1'") shouldBe "r2"
            ds.execute("INSERT INTO hardcover_pushed_reads (user_id, hc_read_id, book_id, recorded_at) VALUES ('u1', 7, 'book1', 1)")
            ds.single("SELECT origin FROM hardcover_pushed_reads WHERE hc_read_id = 7") shouldBe "LIVE"

            ds.execute("DELETE FROM book_reads WHERE id = 'r1'")
            ds.single("SELECT COUNT(*) FROM hardcover_history_reads") shouldBe 1

            ds.execute("DELETE FROM users WHERE id = 'u1'")
            ds.single("SELECT COUNT(*) FROM hardcover_history") shouldBe 0
            ds.single("SELECT COUNT(*) FROM hardcover_history_reads") shouldBe 0
        }
    })
