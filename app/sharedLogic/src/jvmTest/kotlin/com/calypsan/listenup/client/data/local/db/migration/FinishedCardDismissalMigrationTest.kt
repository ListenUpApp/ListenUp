package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_26_27
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v26 → v27: `playback_positions.finishedCardDismissedAt` appears empty, and nothing already in the
 * database is touched — the position rows it extends and the positions sync cursor in particular.
 */
class FinishedCardDismissalMigrationTest :
    FunSpec({
        test("MIGRATION_26_27 adds an empty dismissal column, keeps every row, and validates against 27.json") {
            val helper = createMigrationTestHelper()
            try {
                val v26 = helper.createDatabase(version = 26)
                v26.execSQL(
                    "INSERT INTO playback_positions (bookId, positionMs, playbackSpeed, hasCustomSpeed, volumeBoostDb, " +
                        "hasCustomBoost, updatedAt, lastPlayedAt, isFinished, finishedAt, revision) " +
                        "VALUES ('b1', 9000, 1.0, 0, 0.0, 0, 5, 5, 1, 4, 2)",
                )
                v26.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('playback_positions', 300)")
                v26.close()

                val v27 = helper.runMigrationsAndValidate(version = 27, migrations = listOf(MIGRATION_26_27))

                v27.withStatement("SELECT positionMs, finishedAt, finishedCardDismissedAt FROM playback_positions") { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 9000
                    statement.getLong(1) shouldBe 4
                    statement.isNull(2) shouldBe true
                }
                v27.withStatement("SELECT revision FROM sync_cursor WHERE domainName = 'playback_positions'") { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 300
                }
            } finally {
                helper.close()
            }
        }
    })
