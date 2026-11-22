package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_32_33
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v32 → v33: every existing download reads as never stopped, keeps its state, progress and pause reason, and
 * the empty `download_stop_events` history appears.
 */
class DownloadStopHistoryMigrationTest :
    FunSpec({
        test("MIGRATION_32_33 adds the stop columns and history, keeps every download, and validates against 33.json") {
            val helper = createMigrationTestHelper()
            try {
                val v32 = helper.createDatabase(version = 32)
                v32.execSQL(
                    "INSERT INTO downloads (audioFileId, bookId, filename, fileIndex, state, totalBytes, " +
                        "downloadedBytes, queuedAt, retryCount, pauseReason) " +
                        "VALUES ('af1', 'b1', 'af1.m4b', 0, 'PAUSED', 1000, 400, 0, 1, 'NETWORK_LOST')",
                )
                v32.close()

                val v33 = helper.runMigrationsAndValidate(version = 33, migrations = listOf(MIGRATION_32_33))

                v33.withStatement(
                    "SELECT state, downloadedBytes, retryCount, pauseReason, stopReason, stopCount, lastErrorCode " +
                        "FROM downloads WHERE audioFileId = 'af1'",
                ) { s ->
                    s.step() shouldBe true
                    s.getText(0) shouldBe "PAUSED"
                    s.getLong(1) shouldBe 400
                    s.getLong(2) shouldBe 1
                    s.getText(3) shouldBe "NETWORK_LOST"
                    s.isNull(4) shouldBe true
                    s.getLong(5) shouldBe 0
                    s.isNull(6) shouldBe true
                }
                v33.withStatement("SELECT COUNT(*) FROM download_stop_events") { s ->
                    s.step() shouldBe true
                    s.getLong(0) shouldBe 0
                }
            } finally {
                helper.close()
            }
        }
    })
