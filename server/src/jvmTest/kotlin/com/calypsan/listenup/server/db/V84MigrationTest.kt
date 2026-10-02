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
 * V84 adds `hardcover_api_token` (#1542): the admin's Hardcover API token, sealed. It holds one row at
 * most, a new token replaces the old one, and nothing references a user, so no user's deletion
 * touches it.
 */
class V84MigrationTest :
    FunSpec({
        test("V84 starts empty, holds one token at most, and a new one replaces the old") {
            val path =
                Files
                    .createTempFile("listenup-v84-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 83)

            MigrationRunner(path).migrate()

            ds.single("SELECT COUNT(*) FROM hardcover_api_token") shouldBe 0
            ds.execute(
                "INSERT INTO hardcover_api_token (id, token_enc, hc_username, set_at) VALUES (1, 'sealed-a', 'simon', 10)",
            )
            ds.single("SELECT rejected_at FROM hardcover_api_token WHERE id = 1") shouldBe null
            shouldThrowAny {
                ds.execute(
                    "INSERT INTO hardcover_api_token (id, token_enc, hc_username, set_at) VALUES (2, 'sealed-b', 'other', 11)",
                )
            }
            ds.execute(
                "INSERT OR REPLACE INTO hardcover_api_token (id, token_enc, hc_username, set_at, rejected_at) " +
                    "VALUES (1, 'sealed-b', 'other', 11, NULL)",
            )
            ds.single("SELECT COUNT(*) FROM hardcover_api_token") shouldBe 1
            ds.single("SELECT hc_username FROM hardcover_api_token") shouldBe "other"
        }
    })
