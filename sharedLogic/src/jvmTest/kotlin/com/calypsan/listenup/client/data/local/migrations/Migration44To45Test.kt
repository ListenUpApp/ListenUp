package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain

/**
 * Regression test for [MIGRATION_44_45] — drops the vestigial `clientOpId` columns
 * from `libraries` and `library_folders`.
 *
 * `runMigrationsAndValidate` fails if the migrated shape diverges from the generated
 * v45 schema, so this also proves the DROP COLUMN result matches the entity definitions.
 */
class Migration44To45Test :
    FunSpec({

        test("v44 → v45 drops clientOpId from libraries and library_folders") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 44)

                val db = helper.runMigrationsAndValidate(version = 45, migrations = listOf(MIGRATION_44_45))

                db.columnsOf("libraries") shouldNotContain "clientOpId"
                db.columnsOf("library_folders") shouldNotContain "clientOpId"
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
