package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

/**
 * Regression test for [MIGRATION_34_35] — adds the `book_documents` table.
 *
 * `runMigrationsAndValidate(version = 35, …)` additionally validates the post-migration schema
 * against the exported `35.json`, so a mismatch between the migration SQL and the Room entity
 * (a missing column, wrong type, absent FK/index) fails here, not silently in production.
 */
class Migration34To35Test :
    FunSpec({

        test("v34 → v35 creates the book_documents table with the expected columns") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 34)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 35,
                        migrations = listOf(MIGRATION_34_35),
                    )

                db.columnsOf("book_documents") shouldContainAll
                    listOf("bookId", "index", "id", "filename", "format", "size", "hash")
            } finally {
                helper.close()
            }
        }
    })

/** Column names of [table], read from `PRAGMA table_info`. */
private fun SQLiteConnection.columnsOf(table: String): Set<String> =
    prepare("PRAGMA table_info(`$table`)").use { stmt ->
        buildSet {
            while (stmt.step()) add(stmt.getText(1))
        }
    }
