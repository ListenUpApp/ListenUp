package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.run(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

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

/** V99 gives every framed photo a place for its frame: contributors, users (the avatar's truth) and public_profiles. */
class V99MigrationTest :
    FunSpec({
        test("V99 adds an empty frame column to contributors, users and public_profiles, keeping every row") {
            val path =
                Files
                    .createTempFile("listenup-v99-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 98)
            ds.run(
                "INSERT INTO contributors (id, normalized_name, name, revision, created_at, updated_at, image_path) " +
                    "VALUES ('c1', 'c1', 'c1', 1, 0, 0, 'contributors/abc.jpg')",
            )

            MigrationRunner(path).migrate()

            ds.rows("SELECT image_path, image_frame FROM contributors") shouldBe listOf(listOf("contributors/abc.jpg", null))
            ds.rows("SELECT name FROM pragma_table_info('users') WHERE name = 'avatar_frame'") shouldBe listOf(listOf("avatar_frame"))
            ds.rows("SELECT name FROM pragma_table_info('public_profiles') WHERE name = 'avatar_frame'") shouldBe
                listOf(listOf("avatar_frame"))
        }
    })
