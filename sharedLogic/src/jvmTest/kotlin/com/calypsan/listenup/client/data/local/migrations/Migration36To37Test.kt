package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain

/**
 * Regression test for [MIGRATION_36_37] — adds the `isSystem` column to `collections`.
 *
 * `runMigrationsAndValidate(version = 37, …)` runs the migration and validates the
 * post-migration schema against the exported `37.json`, asserting the `isSystem` column
 * is present after the upgrade.
 */
class Migration36To37Test :
    FunSpec({

        test("v36 → v37 adds the isSystem column to collections") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 36)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 37,
                        migrations = listOf(MIGRATION_36_37),
                    )

                db.columnsOf("collections") shouldContain "isSystem"
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
