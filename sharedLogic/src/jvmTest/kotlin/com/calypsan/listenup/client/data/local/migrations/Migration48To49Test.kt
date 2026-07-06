package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain

/**
 * Regression test for [MIGRATION_48_49] — adds the covering index
 * `index_activities_deletedAt_revision_id` so the sync digest and access-gate live-set reads run as
 * index-only scans over the append-forever `activities` table.
 *
 * `runMigrationsAndValidate` fails if the migrated shape diverges from the generated v49 schema
 * (index names included), so this proves the migration's CREATE INDEX matches the entity definition
 * byte-for-byte; the explicit `PRAGMA index_list` assertion is belt-and-braces documentation.
 */
class Migration48To49Test :
    FunSpec({

        test("v48 → v49 adds the covering digest index on activities") {
            val helper = createMigrationTestHelper()
            try {
                val v48 = helper.createDatabase(version = 48)
                // Seed one live row so the index builds over real data.
                v48.execSQL(
                    "INSERT INTO `activities` (`id`,`userId`,`type`,`occurredAt`,`bookId`,`isReread`," +
                        "`durationMs`,`milestoneValue`,`milestoneUnit`,`shelfId`,`shelfName`,`revision`,`deletedAt`) " +
                        "VALUES ('a1','u1','finished_book',100,'b1',0,3600000,0,NULL,NULL,NULL,5,NULL)",
                )

                val db = helper.runMigrationsAndValidate(version = 49, migrations = listOf(MIGRATION_48_49))

                db.indexNamesOf("activities") shouldContain "index_activities_deletedAt_revision_id"
            } finally {
                helper.close()
            }
        }
    })

/** Index names of [table], read from `PRAGMA index_list`. */
private fun SQLiteConnection.indexNamesOf(table: String): Set<String> =
    prepare("PRAGMA index_list(`$table`)").use { stmt ->
        buildSet {
            while (stmt.step()) add(stmt.getText(1))
        }
    }
