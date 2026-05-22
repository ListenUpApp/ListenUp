package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe

/**
 * Regression test for [MIGRATION_16_17] — the Books-B1 syncable-domain landing
 * for `contributors` and `series`. Asserts both tables gain `revision` and
 * `deletedAt`, and that an existing row survives the rebuild with `revision`
 * defaulted to 0 and `deletedAt` null.
 */
class Migration16To17Test :
    FunSpec({

        test("migrates contributors and series to v17") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 16).apply {
                    execSQL(
                        """
                        INSERT INTO contributors (
                            id, name, sortName, asin, description, imagePath, imageBlurHash,
                            website, birthDate, deathDate, createdAt, updatedAt
                        ) VALUES (
                            'c1', 'Brandon Sanderson', 'Sanderson, Brandon', NULL, NULL,
                            '/images/c1.jpg', NULL, NULL, NULL, NULL, 500, 1000
                        )
                        """.trimIndent(),
                    )
                    execSQL(
                        """
                        INSERT INTO series (
                            id, name, description, asin, coverPath, coverBlurHash,
                            createdAt, updatedAt
                        ) VALUES (
                            's1', 'The Stormlight Archive', NULL, NULL, NULL, NULL, 500, 1000
                        )
                        """.trimIndent(),
                    )
                }

                val db =
                    helper.runMigrationsAndValidate(
                        version = 17,
                        migrations = listOf(MIGRATION_16_17),
                    )

                val contributorColumns = db.columnsOf("contributors")
                contributorColumns shouldContain "revision"
                contributorColumns shouldContain "deletedAt"
                contributorColumns shouldContain "imagePath"
                db
                    .prepare("SELECT name, revision, deletedAt FROM contributors WHERE id = 'c1'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "Brandon Sanderson"
                        stmt.getLong(1) shouldBeExactly 0L
                        stmt.isNull(2).shouldBeTrue()
                    }

                val seriesColumns = db.columnsOf("series")
                seriesColumns shouldContain "revision"
                seriesColumns shouldContain "deletedAt"
                db.prepare("SELECT name, revision, deletedAt FROM series WHERE id = 's1'").use { stmt ->
                    stmt.step().shouldBeTrue()
                    stmt.getText(0) shouldBe "The Stormlight Archive"
                    stmt.getLong(1) shouldBeExactly 0L
                    stmt.isNull(2).shouldBeTrue()
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
