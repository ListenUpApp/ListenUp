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

/**
 * V88 gives the library its Audible store; V89 gives a book its full release date. Both are nullable,
 * and an existing row reads null: the server default store, and no known date.
 */
class V88V89MigrationTest :
    FunSpec({
        test("an existing library has no store set, and an existing book has no release date") {
            val path =
                Files
                    .createTempFile("listenup-v88-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 87)
            ds.execute("INSERT INTO libraries (id, name, created_at, updated_at) VALUES ('lib', 'Library', 0, 0)")
            ds.execute(
                "INSERT INTO books (id, library_id, title, total_duration, root_rel_path, scanned_at, revision, created_at, updated_at, publish_year) " +
                    "VALUES ('b1', 'lib', 'Book', 0, 'b1', 0, 1, 0, 0, 2021)",
            )

            MigrationRunner(path).migrate()

            ds.single("SELECT metadata_region FROM libraries WHERE id = 'lib'") shouldBe null
            ds.single("SELECT release_date FROM books WHERE id = 'b1'") shouldBe null
            ds.single("SELECT publish_year FROM books WHERE id = 'b1'") shouldBe 2021
        }
    })
