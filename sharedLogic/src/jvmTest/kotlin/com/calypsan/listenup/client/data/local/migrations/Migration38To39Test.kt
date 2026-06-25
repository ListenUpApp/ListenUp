package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

/**
 * Regression test for [MIGRATION_38_39] — adds `avatarUpdatedAt` to `public_profiles`.
 */
class Migration38To39Test :
    FunSpec({

        test("v38 → v39 adds avatarUpdatedAt column to public_profiles") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 38)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 39,
                        migrations = listOf(MIGRATION_38_39),
                    )

                db.columnsOf("public_profiles") shouldContainAll setOf("avatarUpdatedAt")
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
