package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Regression test for [MIGRATION_30_31] — rename `activities.createdAt` → `occurredAt`.
 *
 * Asserts:
 * - The column `occurredAt` exists and `createdAt` is gone after migration.
 * - A pre-migration row inserted with `createdAt` survives with its data intact under `occurredAt`.
 * - A post-migration insert using `occurredAt` round-trips correctly.
 */
class Migration30To31Test :
    FunSpec({

        test("v30 → v31 renames createdAt to occurredAt in activities") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 30).apply {
                    // Insert an activity row before migration to verify data survives.
                    execSQL(
                        """
                        INSERT INTO activities
                            (id, userId, type, createdAt, userDisplayName, userAvatarColor, userAvatarType,
                             userAvatarValue, bookId, bookTitle, bookAuthorName, bookCoverPath,
                             isReread, durationMs, milestoneValue, milestoneUnit, shelfId, shelfName)
                        VALUES
                            ('act-1', 'user-1', 'finished_book', 1704067200000, 'Jane Doe', '#FF5733', 'auto',
                             NULL, 'book-1', 'The Way of Kings', 'Brandon Sanderson', NULL,
                             0, 3600000, 0, NULL, NULL, NULL)
                        """.trimIndent(),
                    )
                }

                val db =
                    helper.runMigrationsAndValidate(
                        version = 31,
                        migrations = listOf(MIGRATION_30_31),
                    )

                // occurredAt is present; createdAt is gone
                val columns = db.columnsOf("activities")
                columns shouldContain "occurredAt"
                columns shouldNotContain "createdAt"

                // Pre-migration row survived with data intact under the new column name
                db
                    .prepare("SELECT id, type, occurredAt FROM activities WHERE id = 'act-1'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "act-1"
                        stmt.getText(1) shouldBe "finished_book"
                        stmt.getLong(2) shouldBe 1_704_067_200_000L
                    }
            } finally {
                helper.close()
            }
        }

        test("v30 → v31: round-trip insert with occurredAt into activities succeeds") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 30)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 31,
                        migrations = listOf(MIGRATION_30_31),
                    )

                db.execSQL(
                    """
                    INSERT INTO activities
                        (id, userId, type, occurredAt, userDisplayName, userAvatarColor, userAvatarType,
                         userAvatarValue, bookId, bookTitle, bookAuthorName, bookCoverPath,
                         isReread, durationMs, milestoneValue, milestoneUnit, shelfId, shelfName)
                    VALUES
                        ('act-2', 'user-2', 'started_book', 1704100000000, 'John Smith', '#336699', 'auto',
                         NULL, NULL, NULL, NULL, NULL,
                         0, 0, 0, NULL, NULL, NULL)
                    """.trimIndent(),
                )

                db
                    .prepare("SELECT type, occurredAt FROM activities WHERE id = 'act-2'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "started_book"
                        stmt.getLong(1) shouldBe 1_704_100_000_000L
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
