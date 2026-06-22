package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * Regression test for [MIGRATION_35_36] — adds nullable `partTitle` / `bookTitle` to `chapters`.
 *
 * `runMigrationsAndValidate(version = 36, …)` additionally validates the post-migration schema
 * against the exported `36.json`, so a mismatch between the migration SQL and the Room entity
 * (a missing column, wrong type, absent FK/index) fails here, not silently in production.
 */
class Migration35To36Test :
    FunSpec({

        test("v35 → v36 adds partTitle and bookTitle columns to the chapters table") {
            val helper = createMigrationTestHelper()
            try {
                val v35 = helper.createDatabase(version = 35)

                // Seed a chapter row at v35 — the columns at that version are:
                // id, bookId, title, duration, startTime.
                v35.execSQL(
                    "INSERT INTO `chapters` (`id`, `bookId`, `title`, `duration`, `startTime`) " +
                        "VALUES ('c1', 'b1', 'Chapter One', 300000, 0)",
                )

                val db =
                    helper.runMigrationsAndValidate(
                        version = 36,
                        migrations = listOf(MIGRATION_35_36),
                    )

                // Schema validation: both new columns must be present.
                db.columnsOf("chapters") shouldContainAll listOf("partTitle", "bookTitle")

                // Data continuity: the pre-migration row's new columns read back as NULL.
                db
                    .prepare("SELECT `partTitle`, `bookTitle` FROM `chapters` WHERE `id` = 'c1'")
                    .use { stmt ->
                        stmt.step() shouldBe true
                        stmt.isNull(0).shouldBeTrue() // partTitle is NULL for a pre-migration row
                        stmt.isNull(1).shouldBeTrue() // bookTitle is NULL for a pre-migration row
                    }
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
