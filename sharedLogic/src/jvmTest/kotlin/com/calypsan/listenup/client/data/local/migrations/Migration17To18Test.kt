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
 * Regression test for [MIGRATION_17_18] — the syncable-domain landing
 * for `playback_positions`. Asserts the table gains `revision` and `deletedAt`, and
 * that an existing row survives the rebuild with `revision = 0` and `deletedAt` null.
 * All pre-existing columns (bookId, positionMs, playbackSpeed, hasCustomSpeed, updatedAt,
 * syncedAt, lastPlayedAt, isFinished, finishedAt, startedAt) are preserved.
 */
class Migration17To18Test :
    FunSpec({

        test("migrates playback_positions to v18") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 17).apply {
                    execSQL(
                        """
                        INSERT INTO playback_positions (
                            bookId, positionMs, playbackSpeed, hasCustomSpeed, updatedAt,
                            syncedAt, lastPlayedAt, isFinished, finishedAt, startedAt
                        ) VALUES (
                            'book1', 12345, 1.0, 0, 1000,
                            NULL, 900, 0, NULL, 800
                        )
                        """.trimIndent(),
                    )
                }

                val db =
                    helper.runMigrationsAndValidate(
                        version = 18,
                        migrations = listOf(MIGRATION_17_18),
                    )

                val columns = db.columnsOf("playback_positions")
                columns shouldContain "revision"
                columns shouldContain "deletedAt"
                columns shouldContain "bookId"
                columns shouldContain "positionMs"
                columns shouldContain "playbackSpeed"
                columns shouldContain "hasCustomSpeed"
                columns shouldContain "updatedAt"
                columns shouldContain "syncedAt"
                columns shouldContain "lastPlayedAt"
                columns shouldContain "isFinished"
                columns shouldContain "finishedAt"
                columns shouldContain "startedAt"

                db
                    .prepare(
                        "SELECT positionMs, playbackSpeed, hasCustomSpeed, updatedAt, " +
                            "syncedAt, lastPlayedAt, isFinished, finishedAt, startedAt, " +
                            "revision, deletedAt FROM playback_positions WHERE bookId = 'book1'",
                    ).use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getLong(0) shouldBeExactly 12345L
                        stmt.getDouble(1) shouldBe 1.0
                        stmt.getLong(2) shouldBeExactly 0L // hasCustomSpeed = false
                        stmt.getLong(3) shouldBeExactly 1000L
                        stmt.isNull(4).shouldBeTrue() // syncedAt = NULL
                        stmt.getLong(5) shouldBeExactly 900L
                        stmt.getLong(6) shouldBeExactly 0L // isFinished = false
                        stmt.isNull(7).shouldBeTrue() // finishedAt = NULL
                        stmt.getLong(8) shouldBeExactly 800L
                        stmt.getLong(9) shouldBeExactly 0L // revision = 0
                        stmt.isNull(10).shouldBeTrue() // deletedAt = NULL
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
