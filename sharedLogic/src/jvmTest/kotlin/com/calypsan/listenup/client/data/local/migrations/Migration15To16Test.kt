package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeExactly

/**
 * Regression test for [MIGRATION_15_16] — the Scanner Polish scan-warning advisory.
 *
 * Drives a real v15 database through the migration and asserts the structural
 * outcome: `books` gains the new `hasScanWarning` column, it defaults to 0 for
 * pre-existing rows, and existing row data survives the column addition.
 */
class Migration15To16Test :
    FunSpec({

        test("adds hasScanWarning column to books, defaulting to 0") {
            val helper = createMigrationTestHelper()
            try {
                // Seed a v15 database with one book row.
                helper.createDatabase(version = 15).apply {
                    execSQL(
                        """
                        INSERT INTO books (
                            id, title, sortTitle, subtitle, coverHash, coverBlurHash,
                            dominantColor, darkMutedColor, vibrantColor, totalDuration,
                            description, publishYear, publisher, language, isbn, asin,
                            abridged, revision, deletedAt, createdAt, updatedAt
                        ) VALUES (
                            'book1', 'The Way of Kings', 'Way of Kings, The', NULL,
                            NULL, 'LKO2?U%2Tw=w', NULL, NULL, NULL, 3600000,
                            'An epic.', 2010, 'Tor', 'en', NULL, NULL,
                            0, 0, NULL, 500, 1000
                        )
                        """.trimIndent(),
                    )
                }

                // Run the migration and validate against the exported v16 schema.
                val db =
                    helper.runMigrationsAndValidate(
                        version = 16,
                        migrations = listOf(MIGRATION_15_16),
                    )

                // books: hasScanWarning column present, defaults to 0 for the existing row.
                db.columnsOf("books") shouldContain "hasScanWarning"
                db.prepare("SELECT hasScanWarning FROM books WHERE id = 'book1'").use { stmt ->
                    stmt.step().shouldBeTrue()
                    stmt.getLong(0) shouldBeExactly 0L
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
