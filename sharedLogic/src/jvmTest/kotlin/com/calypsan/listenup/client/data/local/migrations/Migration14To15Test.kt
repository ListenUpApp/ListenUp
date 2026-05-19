package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe

/**
 * Regression test for [MIGRATION_14_15] — the Books-A sync substrate migration.
 *
 * Drives a real v14 database through the migration and asserts the structural
 * outcome: the `Syncable` columns are gone, `books` carries the new `revision`
 * and `deletedAt` columns, the cover columns are renamed, and existing row
 * data survives the create-new → copy → drop → rename rebuild.
 */
class Migration14To15Test :
    FunSpec({

        test("migrates books, chapters, series and contributors to v15") {
            val helper = createMigrationTestHelper()
            try {
                // Seed a v14 database with one row per affected table.
                helper.createDatabase(version = 14).apply {
                    execSQL(
                        """
                        INSERT INTO books (
                            id, title, sortTitle, subtitle, coverUrl, coverBlurHash,
                            dominantColor, darkMutedColor, vibrantColor, totalDuration,
                            description, publishYear, publisher, language, isbn, asin,
                            abridged, syncState, lastModified, serverVersion, createdAt, updatedAt
                        ) VALUES (
                            'book1', 'The Way of Kings', 'Way of Kings, The', NULL,
                            '/covers/book1.jpg', 'LKO2?U%2Tw=w', NULL, NULL, NULL, 3600000,
                            'An epic.', 2010, 'Tor', 'en', NULL, NULL,
                            0, 'SYNCED', 1000, 1000, 500, 1000
                        )
                        """.trimIndent(),
                    )
                    execSQL(
                        """
                        INSERT INTO chapters (
                            id, bookId, title, duration, startTime,
                            syncState, lastModified, serverVersion
                        ) VALUES ('book1_0', 'book1', 'Prologue', 600000, 0, 'SYNCED', 1000, 1000)
                        """.trimIndent(),
                    )
                    execSQL(
                        """
                        INSERT INTO series (
                            id, name, description, asin, coverImagePath, coverBlurHash,
                            syncState, lastModified, serverVersion, createdAt, updatedAt
                        ) VALUES (
                            'series1', 'The Stormlight Archive', 'A series.', NULL,
                            '/covers/series1.jpg', 'LKO2?U%2Tw=w', 'SYNCED', 1000, 1000, 500, 1000
                        )
                        """.trimIndent(),
                    )
                    execSQL(
                        """
                        INSERT INTO contributors (
                            id, name, sortName, asin, description, imagePath, imageBlurHash,
                            website, birthDate, deathDate,
                            syncState, lastModified, serverVersion, createdAt, updatedAt
                        ) VALUES (
                            'contrib1', 'Brandon Sanderson', 'Sanderson, Brandon', NULL,
                            NULL, '/images/contrib1.jpg', NULL, NULL, NULL, NULL,
                            'SYNCED', 1000, 1000, 500, 1000
                        )
                        """.trimIndent(),
                    )
                }

                // Run the migration and validate against the exported v15 schema.
                val db =
                    helper.runMigrationsAndValidate(
                        version = 15,
                        migrations = listOf(MIGRATION_14_15),
                    )

                // books: Syncable columns dropped, coverHash present (null — not
                // carried), revision defaults to 0, deletedAt null.
                val bookColumns = db.columnsOf("books")
                bookColumns shouldNotContain "syncState"
                bookColumns shouldNotContain "lastModified"
                bookColumns shouldNotContain "serverVersion"
                bookColumns shouldNotContain "coverUrl"
                bookColumns shouldContain "coverHash"
                bookColumns shouldContain "revision"
                bookColumns shouldContain "deletedAt"

                db
                    .prepare(
                        "SELECT title, coverHash, revision, deletedAt FROM books WHERE id = 'book1'",
                    ).use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "The Way of Kings"
                        stmt.isNull(1).shouldBeTrue() // coverHash starts null — old coverUrl not carried
                        stmt.getLong(2) shouldBeExactly 0L // revision defaults to 0
                        stmt.isNull(3).shouldBeTrue() // deletedAt null for a live book
                    }

                // chapters: Syncable columns dropped, index recreated, row survives.
                db.columnsOf("chapters") shouldNotContain "syncState"
                db.indicesOf("chapters") shouldContain "index_chapters_bookId"
                db.prepare("SELECT title FROM chapters WHERE id = 'book1_0'").use { stmt ->
                    stmt.step().shouldBeTrue()
                    stmt.getText(0) shouldBe "Prologue"
                }

                // series: Syncable columns dropped, coverImagePath renamed to
                // coverPath with the value carried across.
                val seriesColumns = db.columnsOf("series")
                seriesColumns shouldNotContain "syncState"
                seriesColumns shouldNotContain "coverImagePath"
                seriesColumns shouldContain "coverPath"
                db.prepare("SELECT name, coverPath FROM series WHERE id = 'series1'").use { stmt ->
                    stmt.step().shouldBeTrue()
                    stmt.getText(0) shouldBe "The Stormlight Archive"
                    stmt.getText(1) shouldBe "/covers/series1.jpg" // coverPath carries coverImagePath
                }

                // contributors: Syncable columns dropped, imagePath unchanged.
                val contributorColumns = db.columnsOf("contributors")
                contributorColumns shouldNotContain "syncState"
                contributorColumns shouldContain "imagePath"
                db
                    .prepare(
                        "SELECT name, imagePath FROM contributors WHERE id = 'contrib1'",
                    ).use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "Brandon Sanderson"
                        stmt.getText(1) shouldBe "/images/contrib1.jpg"
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

/** Index names defined on [table], read from `PRAGMA index_list`. */
private fun SQLiteConnection.indicesOf(table: String): Set<String> =
    prepare("PRAGMA index_list(`$table`)").use { stmt ->
        buildSet {
            while (stmt.step()) add(stmt.getText(1))
        }
    }
