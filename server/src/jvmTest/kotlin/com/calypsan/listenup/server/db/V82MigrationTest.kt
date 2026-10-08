package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.execute(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

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

private fun DataSource.columns(table: String): List<String> =
    rows("PRAGMA table_info('$table')").map { row -> checkNotNull(row[1]) as String }

/** A database migrated up to just before V82, as a server running main today holds it. */
private fun databaseBeforeV82(): Pair<String, DataSource> {
    val path =
        Files
            .createTempFile("listenup-v82-", ".db")
            .toFile()
            .apply { deleteOnExit() }
            .absolutePath
    val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
    MigrationRunner(path).migrate(upTo = 80)
    return path to ds
}

/**
 * V82 drops the "Can share" permission. Since collection writes became admin-only (#1548) the flag gated
 * nothing, so both columns go — and every user, and every roster row, must survive the drop untouched.
 */
class V82MigrationTest :
    FunSpec({
        test("V82 drops can_share from users and admin_user_roster and keeps every row") {
            val (path, ds) = databaseBeforeV82()
            ds.columns("users") shouldContain "can_share"
            ds.columns("admin_user_roster") shouldContain "can_share"
            ds.execute(
                "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at, can_edit, can_share) " +
                    "VALUES ('u1', 'a@x', 'a@x', 'phc', 'MEMBER', 'Alice', 'ACTIVE', 1, 2, 0, 0), " +
                    "('u2', 'b@x', 'b@x', 'phc', 'ADMIN', 'Bob', 'ACTIVE', 3, 4, 1, 1)",
            )
            ds.execute(
                "INSERT INTO admin_user_roster (id, email, display_name, role, status, can_share, can_edit, " +
                    "account_created_at, created_at, updated_at, revision) " +
                    "VALUES ('u1', 'a@x', 'Alice', 'MEMBER', 'ACTIVE', 0, 0, 1, 1, 2, 7)",
            )

            MigrationRunner(path).migrate()

            ds.columns("users") shouldNotContain "can_share"
            ds.columns("admin_user_roster") shouldNotContain "can_share"
            ds.rows("SELECT id, display_name, role, can_edit FROM users ORDER BY id") shouldBe
                listOf(listOf("u1", "Alice", "MEMBER", 0), listOf("u2", "Bob", "ADMIN", 1))
            ds.rows("SELECT id, display_name, can_edit, revision FROM admin_user_roster") shouldBe
                listOf(listOf("u1", "Alice", 0, 7))
        }
    })
