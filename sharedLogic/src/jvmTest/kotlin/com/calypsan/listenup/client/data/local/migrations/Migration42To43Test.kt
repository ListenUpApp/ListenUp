package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

/**
 * Regression test for [MIGRATION_42_43] — adds the `coverDownloadedAt` cover-presence
 * marker column to `books`.
 *
 * `runMigrationsAndValidate(version = 43, …)` runs the migration and validates the
 * post-migration schema against the exported `43.json`, asserting the new column is
 * present after the upgrade.
 */
class Migration42To43Test :
    FunSpec({

        test("v42 → v43 adds the coverDownloadedAt column to books") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 42)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 43,
                        migrations = listOf(MIGRATION_42_43),
                    )

                db.columnsOf("books") shouldContainAll setOf("coverDownloadedAt")
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
