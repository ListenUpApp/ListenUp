package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

/**
 * Regression test for [MIGRATION_40_41] — adds the `userEditedFields` provenance column to `books`.
 */
class Migration40To41Test :
    FunSpec({

        test("v40 → v41 adds userEditedFields column to books") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 40)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 41,
                        migrations = listOf(MIGRATION_40_41),
                    )

                db.columnsOf("books") shouldContainAll setOf("userEditedFields")
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
