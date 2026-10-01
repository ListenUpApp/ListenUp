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
 * V79 adds `hardcover_preferences` (#1538). An existing connected user gets no row, which means As I
 * listen, so nobody's behaviour changes. One row per user, gone with the user.
 */
class V79MigrationTest :
    FunSpec({
        test("V79 gives existing users no preference, holds one row per user, and cascades with the user") {
            val path =
                Files
                    .createTempFile("listenup-v79-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 78)
            ds.execute(
                "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
                    "VALUES ('u1', 'u1@x', 'u1@x', 'phc', 'MEMBER', 'u1', 'ACTIVE', 0, 0)",
            )
            ds.execute(
                "INSERT INTO hardcover_connections (user_id, hc_user_id, hc_username, access_token_enc, access_expires_at, " +
                    "refresh_token_enc, refresh_expires_at, scopes, connected_at) VALUES ('u1', 42, 'simon', 'a', 1, 'r', 2, 's', 3)",
            )

            MigrationRunner(path).migrate()

            ds.single("SELECT COUNT(*) FROM hardcover_preferences") shouldBe 0
            ds.execute("INSERT INTO hardcover_preferences (user_id, share_mode, updated_at) VALUES ('u1', 'FINISHED_ONLY', 10)")
            shouldThrowAny {
                ds.execute("INSERT INTO hardcover_preferences (user_id, share_mode, updated_at) VALUES ('u1', 'AS_I_LISTEN', 11)")
            }
            ds.single("SELECT share_mode FROM hardcover_preferences WHERE user_id = 'u1'") shouldBe "FINISHED_ONLY"

            ds.execute("DELETE FROM users WHERE id = 'u1'")
            ds.single("SELECT COUNT(*) FROM hardcover_preferences") shouldBe 0
        }
    })
