package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Regression test for [MIGRATION_19_20] — Books-B2a series enrichment.
 *
 * Asserts:
 * - `series` gains the `sortName` column (nullable TEXT).
 * - A pre-migration row survives with its data intact and `sortName` defaulting to null.
 * - A post-migration insert of `sortName` succeeds and round-trips correctly.
 */
class Migration19To20Test :
    FunSpec({

        test("v19 → v20 adds sortName column to series") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 19).apply {
                    // Insert a series row before migration to verify data survives.
                    execSQL(
                        """
                        INSERT INTO series
                            (id, name, description, asin, coverPath, coverBlurHash, revision, deletedAt, createdAt, updatedAt)
                        VALUES
                            ('s1', 'The Stormlight Archive', 'Epic fantasy', NULL, NULL, NULL, 1, NULL, 1000, 1000)
                        """.trimIndent(),
                    )
                }

                val db =
                    helper.runMigrationsAndValidate(
                        version = 20,
                        migrations = listOf(MIGRATION_19_20),
                    )

                // New column is present
                val columns = db.columnsOf("series")
                columns shouldContain "sortName"

                // Pre-migration row survived and sortName defaults to null
                db
                    .prepare("SELECT id, name, sortName FROM series WHERE id = 's1'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "s1"
                        stmt.getText(1) shouldBe "The Stormlight Archive"
                        stmt.isNull(2).shouldBeTrue()
                    }
            } finally {
                helper.close()
            }
        }

        test("v19 → v20: round-trip insert with sortName into series succeeds") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 19)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 20,
                        migrations = listOf(MIGRATION_19_20),
                    )

                db.execSQL(
                    """
                    INSERT INTO series
                        (id, name, sortName, description, asin, coverPath, coverBlurHash, revision, deletedAt, createdAt, updatedAt)
                    VALUES
                        ('s2', 'Mistborn', 'Sanderson, Brandon — Mistborn', 'Epic fantasy', NULL, NULL, NULL, 1, NULL, 2000, 2000)
                    """.trimIndent(),
                )

                db
                    .prepare("SELECT name, sortName FROM series WHERE id = 's2'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "Mistborn"
                        stmt.getText(1) shouldBe "Sanderson, Brandon — Mistborn"
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
