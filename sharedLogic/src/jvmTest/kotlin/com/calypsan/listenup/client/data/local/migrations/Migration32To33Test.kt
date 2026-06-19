package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Regression test for [MIGRATION_32_33] — drops the unused cover-color columns from `books`.
 *
 * Asserts:
 * - `books` no longer has `dominantColor`, `darkMutedColor`, or `vibrantColor`.
 * - The surviving columns remain, and a pre-migration row keeps its non-color data intact.
 */
class Migration32To33Test :
    FunSpec({

        test("v32 → v33 drops the cover-color columns from books") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 32).apply {
                    // Seed a row (with the soon-to-be-dropped color columns populated) to prove the
                    // non-color data survives the table rebuild.
                    execSQL(
                        """
                        INSERT INTO books
                            (id, libraryId, folderId, title, sortTitle, subtitle, coverHash, coverBlurHash,
                             dominantColor, darkMutedColor, vibrantColor, totalDuration, description, publishYear,
                             publisher, language, isbn, asin, abridged, revision, deletedAt, hasScanWarning,
                             createdAt, updatedAt)
                        VALUES
                            ('b1', 'lib1', 'f1', 'The Way of Kings', NULL, NULL, NULL, NULL,
                             123, 456, 789, 3600000, 'Epic', 2010,
                             'Tor', 'en', NULL, 'B001', 0, 1, NULL, 0,
                             1000, 1000)
                        """.trimIndent(),
                    )
                }

                val db =
                    helper.runMigrationsAndValidate(
                        version = 33,
                        migrations = listOf(MIGRATION_32_33),
                    )

                // Color columns are gone; the rest of the schema survives.
                val columns = db.columnsOf("books")
                columns shouldNotContain "dominantColor"
                columns shouldNotContain "darkMutedColor"
                columns shouldNotContain "vibrantColor"
                columns shouldContainAll
                    listOf("id", "title", "coverHash", "coverBlurHash", "revision", "hasScanWarning")

                // Pre-migration row survived with its non-color data intact.
                db
                    .prepare("SELECT id, title, totalDuration, publisher FROM books WHERE id = 'b1'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "b1"
                        stmt.getText(1) shouldBe "The Way of Kings"
                        stmt.getLong(2) shouldBe 3_600_000L
                        stmt.getText(3) shouldBe "Tor"
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
