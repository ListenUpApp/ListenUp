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
 * Regression test for [MIGRATION_18_19] — the Playback-P2 stats domain landing.
 *
 * Asserts:
 * - `listening_events` is rebuilt with the P2 schema (userId, tz, deviceLabel, revision,
 *   deletedAt; old columns deviceId, syncState, source, createdAt are gone).
 * - `user_stats` is rebuilt with the P2 stats shape (id, totalSecondsAllTime, …, revision,
 *   deletedAt; old leaderboard columns are gone).
 * - `tentative_span` is created with all expected columns.
 * - The three composite indexes on `listening_events` exist.
 * - A row inserted into the pre-migration tables does not survive (rows are discarded —
 *   pre-launch, no production data at risk).
 */
class Migration18To19Test :
    FunSpec({

        test("v18 → v19 rebuilds listening_events with P2 schema") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 18).apply {
                    // Insert a row in the old schema to confirm it doesn't survive (rows discarded).
                    execSQL(
                        """
                        INSERT INTO listening_events
                            (id, bookId, startPositionMs, endPositionMs, startedAt, endedAt,
                             playbackSpeed, deviceId, syncState, createdAt, source)
                        VALUES
                            ('evt1', 'book1', 0, 60000, 1000, 2000, 1.0, 'dev1', 'NOT_SYNCED', 1000, 'playback')
                        """.trimIndent(),
                    )
                }

                val db =
                    helper.runMigrationsAndValidate(
                        version = 19,
                        migrations = listOf(MIGRATION_18_19),
                    )

                val columns = db.columnsOf("listening_events")
                // New P2 columns
                columns shouldContain "id"
                columns shouldContain "userId"
                columns shouldContain "bookId"
                columns shouldContain "startPositionMs"
                columns shouldContain "endPositionMs"
                columns shouldContain "startedAt"
                columns shouldContain "endedAt"
                columns shouldContain "playbackSpeed"
                columns shouldContain "tz"
                columns shouldContain "deviceLabel"
                columns shouldContain "revision"
                columns shouldContain "deletedAt"
                // Old columns should be gone
                columns shouldNotContain "deviceId"
                columns shouldNotContain "syncState"
                columns shouldNotContain "source"
                columns shouldNotContain "createdAt"

                // Table must be empty after migration (rows discarded)
                db.countOf("listening_events") shouldBe 0L
            } finally {
                helper.close()
            }
        }

        test("v18 → v19 rebuilds user_stats with P2 stats schema") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 18).apply {
                    execSQL(
                        """
                        INSERT INTO user_stats
                            (userId, displayName, avatarColor, avatarType, avatarValue,
                             totalTimeMs, totalBooks, currentStreak, updatedAt)
                        VALUES
                            ('u1', 'Alice', '#ff0000', 'auto', NULL, 3600000, 5, 3, 1000)
                        """.trimIndent(),
                    )
                }

                val db =
                    helper.runMigrationsAndValidate(
                        version = 19,
                        migrations = listOf(MIGRATION_18_19),
                    )

                val columns = db.columnsOf("user_stats")
                // New P2 columns
                columns shouldContain "id"
                columns shouldContain "totalSecondsAllTime"
                columns shouldContain "totalSecondsLast7Days"
                columns shouldContain "totalSecondsLast30Days"
                columns shouldContain "booksStarted"
                columns shouldContain "booksFinished"
                columns shouldContain "currentStreakDays"
                columns shouldContain "longestStreakDays"
                columns shouldContain "lastEventDate"
                columns shouldContain "revision"
                columns shouldContain "deletedAt"
                // Old leaderboard columns should be gone
                columns shouldNotContain "userId"
                columns shouldNotContain "displayName"
                columns shouldNotContain "avatarColor"
                columns shouldNotContain "totalTimeMs"
                columns shouldNotContain "totalBooks"
                columns shouldNotContain "currentStreak"

                // Table must be empty after migration (rows discarded)
                db.countOf("user_stats") shouldBe 0L
            } finally {
                helper.close()
            }
        }

        test("v18 → v19 creates tentative_span table") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 18)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 19,
                        migrations = listOf(MIGRATION_18_19),
                    )

                val columns = db.columnsOf("tentative_span")
                columns shouldContain "id"
                columns shouldContain "userId"
                columns shouldContain "bookId"
                columns shouldContain "startPositionMs"
                columns shouldContain "currentPositionMs"
                columns shouldContain "startedAt"
                columns shouldContain "lastHeartbeatAt"
                columns shouldContain "playbackSpeed"
                columns shouldContain "tz"
                columns shouldContain "deviceLabel"
            } finally {
                helper.close()
            }
        }

        test("v18 → v19 creates composite indexes on listening_events") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 18)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 19,
                        migrations = listOf(MIGRATION_18_19),
                    )

                val indexes = db.indexesOf("listening_events")
                indexes shouldContain "index_listening_events_userId_endedAt"
                indexes shouldContain "index_listening_events_userId_revision"
                indexes shouldContain "index_listening_events_userId_bookId"
            } finally {
                helper.close()
            }
        }

        test("v18 → v19: round-trip insert into rebuilt listening_events succeeds") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 18)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 19,
                        migrations = listOf(MIGRATION_18_19),
                    )

                db.execSQL(
                    """
                    INSERT INTO listening_events
                        (id, userId, bookId, startPositionMs, endPositionMs, startedAt, endedAt,
                         playbackSpeed, tz, deviceLabel, revision, deletedAt)
                    VALUES
                        ('evt2', 'u1', 'book2', 0, 60000, 1000, 2000, 1.5, 'Europe/London', NULL, 0, NULL)
                    """.trimIndent(),
                )

                db
                    .prepare("SELECT userId, tz, revision FROM listening_events WHERE id = 'evt2'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "u1"
                        stmt.getText(1) shouldBe "Europe/London"
                        stmt.getLong(2) shouldBe 0L
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

/** Index names on [table], read from `PRAGMA index_list`. */
private fun SQLiteConnection.indexesOf(table: String): Set<String> =
    prepare("PRAGMA index_list(`$table`)").use { stmt ->
        buildSet {
            while (stmt.step()) add(stmt.getText(1))
        }
    }

/** Row count for [table]. */
private fun SQLiteConnection.countOf(table: String): Long =
    prepare("SELECT COUNT(*) FROM `$table`").use { stmt ->
        check(stmt.step())
        stmt.getLong(0)
    }
