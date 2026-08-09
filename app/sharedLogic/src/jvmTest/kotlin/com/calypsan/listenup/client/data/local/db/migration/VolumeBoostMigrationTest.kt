package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_1_2
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates the first-ever schema migration (v1 → v2): the volume-boost + loudness-normalization
 * columns land as pure `ADD COLUMN`s, pre-existing rows survive byte-for-byte, and the migrated
 * database passes Room's full schema validation against the exported `2.json`.
 *
 * Data survival is the point, not a formality: the local DB holds the unsynced outbox and
 * `syncedAt`-pending rows, so this migration must be non-destructive (see the migration policy in
 * `ListenUpDatabase` and [DatabaseMigrationPolicyTest]).
 */
class VolumeBoostMigrationTest :
    FunSpec({
        test("MIGRATION_1_2 preserves existing rows and adds volume-boost columns with defaults") {
            val helper = createMigrationTestHelper()
            try {
                val v1 = helper.createDatabase(version = 1)
                v1.execSQL(
                    """
                    INSERT INTO playback_positions
                        (bookId, positionMs, playbackSpeed, hasCustomSpeed, updatedAt,
                         isFinished, revision)
                    VALUES ('book1', 1234, 1.5, 1, 1000, 0, 0)
                    """.trimIndent(),
                )
                v1.execSQL(
                    """
                    INSERT INTO user_preferences
                        (id, defaultPlaybackSpeed, defaultSkipForwardSec, defaultSkipBackwardSec,
                         defaultSleepTimerMin)
                    VALUES ('user1', 1.25, 30, 10, NULL)
                    """.trimIndent(),
                )
                v1.close()

                // Runs the migration AND validates the migrated schema against the exported 2.json
                // (identity hash + table shape) — proving the ALTERs match what Room expects.
                val v2 = helper.runMigrationsAndValidate(version = 2, migrations = listOf(MIGRATION_1_2))

                v2.withStatement(
                    """
                    SELECT positionMs, playbackSpeed, hasCustomSpeed,
                           volumeBoostDb, hasCustomBoost, measuredGainDb
                    FROM playback_positions WHERE bookId = 'book1'
                    """.trimIndent(),
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 1234L
                    statement.getDouble(1) shouldBe 1.5
                    statement.getLong(2) shouldBe 1L
                    statement.getDouble(3) shouldBe 0.0
                    statement.getLong(4) shouldBe 0L
                    statement.isNull(5) shouldBe true
                    statement.step() shouldBe false
                }

                v2.withStatement(
                    """
                    SELECT defaultPlaybackSpeed, defaultVolumeBoostDb
                    FROM user_preferences WHERE id = 'user1'
                    """.trimIndent(),
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getDouble(0) shouldBe 1.25
                    statement.getDouble(1) shouldBe 0.0
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })

/** Prepares [sql], runs [block] against the statement, and always closes it. */
private inline fun <T> SQLiteConnection.withStatement(
    sql: String,
    block: (SQLiteStatement) -> T,
): T {
    val statement = prepare(sql)
    return try {
        block(statement)
    } finally {
        statement.close()
    }
}
