package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
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

private fun DataSource.columns(table: String): List<String> = rows("PRAGMA table_info('$table')").map { it[1] as String }

/** A database migrated up to just before V92, as a server running main today holds it. */
private fun databaseBeforeV92(): Pair<String, DataSource> {
    val path =
        Files
            .createTempFile("listenup-v92-", ".db")
            .toFile()
            .apply { deleteOnExit() }
            .absolutePath
    val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
    MigrationRunner(path).migrate(upTo = 91)
    return path to ds
}

/**
 * V92 splits "Can edit" into Edit metadata and Curate library. Nobody loses a power: every user — and
 * every roster row — that holds can_edit today holds can_curate_library after it. A user created
 * afterwards gets the column default, off.
 */
class V92MigrationTest :
    FunSpec({
        test("V92 backfills can_curate_library from can_edit on users and the roster, and new users default off") {
            val (path, ds) = databaseBeforeV92()
            ds.execute(
                "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at, can_edit) " +
                    "VALUES ('u1', 'a@x', 'a@x', 'phc', 'MEMBER', 'Alice', 'ACTIVE', 1, 2, 1), " +
                    "('u2', 'b@x', 'b@x', 'phc', 'MEMBER', 'Bob', 'ACTIVE', 3, 4, 0)",
            )
            ds.execute(
                "INSERT INTO admin_user_roster (id, email, display_name, role, status, can_edit, " +
                    "account_created_at, created_at, updated_at, revision) " +
                    "VALUES ('u1', 'a@x', 'Alice', 'MEMBER', 'ACTIVE', 1, 1, 1, 2, 7), " +
                    "('u2', 'b@x', 'Bob', 'MEMBER', 'ACTIVE', 0, 3, 3, 4, 8)",
            )

            MigrationRunner(path).migrate()

            ds.columns("users") shouldContain "can_curate_library"
            ds.columns("admin_user_roster") shouldContain "can_curate_library"
            ds.rows("SELECT id, can_edit, can_curate_library FROM users ORDER BY id") shouldBe
                listOf(listOf("u1", 1, 1), listOf("u2", 0, 0))
            ds.rows("SELECT id, can_edit, can_curate_library, revision FROM admin_user_roster ORDER BY id") shouldBe
                listOf(listOf("u1", 1, 1, 7), listOf("u2", 0, 0, 8))

            ds.execute(
                "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at) VALUES ('u3', 'c@x', 'c@x', 'phc', 'MEMBER', 'Cleo', 'ACTIVE', 5, 6)",
            )
            ds.rows("SELECT can_edit, can_curate_library FROM users WHERE id = 'u3'") shouldBe listOf(listOf(1, 0))
        }
    })
