package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.run(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.rotationColumns(id: String): Pair<Long?, Long?> =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT rotated_at, rotation_confirmed_at FROM sessions WHERE id = '$id'").use { rs ->
                rs.next()
                (rs.getObject(1) as Number?)?.toLong() to (rs.getObject(2) as Number?)?.toLong()
            }
        }
    }

/**
 * V78 adds the rotation-confirmation columns. A session that existed before it is backfilled as
 * confirmed at its last rotation, so a deploy never opens an existing session to the lost-reply
 * re-rotation it had no evidence for; a session created afterwards starts with both columns empty.
 */
class V78MigrationTest :
    FunSpec({
        test("V78 backfills existing sessions as confirmed and leaves new ones unconfirmed") {
            val path =
                Files
                    .createTempFile("listenup-v78-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 77)
            ds.run(
                "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
                    "VALUES ('u1', 'u1@x', 'u1@x', 'phc', 'MEMBER', 'u1', 'ACTIVE', 0, 0)",
            )
            ds.run(
                "INSERT INTO sessions (id, user_id, refresh_token_hash, family_id, previous_hash, created_at, expires_at, last_used_at) " +
                    "VALUES ('old', 'u1', 'h1', 'f1', 'h0', 100, 999999, 500)",
            )

            MigrationRunner(path).migrate()

            ds.rotationColumns("old") shouldBe (500L to 500L)
            ds.run(
                "INSERT INTO sessions (id, user_id, refresh_token_hash, family_id, created_at, expires_at, last_used_at) " +
                    "VALUES ('new', 'u1', 'h2', 'f2', 600, 999999, 600)",
            )
            ds.rotationColumns("new") shouldBe (null to null)
        }
    })
