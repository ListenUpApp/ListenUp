package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_31_32
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v31 → v32: `downloads.pauseReason` appears empty on every existing row, and a download's state and
 * progress are untouched, so a paused download still resumes from the bytes it has.
 */
class DownloadPauseReasonMigrationTest :
    FunSpec({
        test("MIGRATION_31_32 adds an empty pause reason, keeps state and progress, and validates against 32.json") {
            val helper = createMigrationTestHelper()
            try {
                val v31 = helper.createDatabase(version = 31)
                v31.execSQL(
                    "INSERT INTO downloads (audioFileId, bookId, filename, fileIndex, state, totalBytes, " +
                        "downloadedBytes, queuedAt, retryCount) VALUES ('af1', 'b1', 'af1.m4b', 0, 'PAUSED', 1000, 400, 0, 0)",
                )
                v31.close()

                val v32 = helper.runMigrationsAndValidate(version = 32, migrations = listOf(MIGRATION_31_32))

                v32.withStatement("SELECT state, downloadedBytes, pauseReason FROM downloads WHERE audioFileId = 'af1'") { s ->
                    s.step() shouldBe true
                    s.getText(0) shouldBe "PAUSED"
                    s.getLong(1) shouldBe 400
                    s.isNull(2) shouldBe true
                }
            } finally {
                helper.close()
            }
        }
    })
