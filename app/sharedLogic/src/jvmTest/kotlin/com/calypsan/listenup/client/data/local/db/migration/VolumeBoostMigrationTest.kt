package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_1_2
import com.calypsan.listenup.client.data.local.db.MIGRATION_2_3
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates the volume-boost schema migrations: v1 → v2 (volume-boost + loudness-normalization
 * columns on `playback_positions`/`user_preferences`) and v2 → v3 (the `books.normalizationGainDb`
 * tag-fallback column). Both land as pure `ADD COLUMN`s, pre-existing rows survive byte-for-byte,
 * and the migrated database passes Room's full schema validation against the exported schema JSON.
 *
 * Data survival is the point, not a formality: the local DB holds the unsynced outbox and
 * `syncedAt`-pending rows, so these migrations must be non-destructive (see the migration policy in
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

        test("MIGRATION_2_3 preserves existing rows and adds a nullable normalizationGainDb column") {
            val helper = createMigrationTestHelper()
            try {
                val v2 = helper.createDatabase(version = 2)
                v2.execSQL(
                    """
                    INSERT INTO books
                        (id, libraryId, folderId, title, totalDuration, abridged, revision,
                         hasScanWarning, createdAt, updatedAt)
                    VALUES ('book1', 'lib1', 'folder1', 'Test Book', 3600000, 0, 0, 0, 1000, 1000)
                    """.trimIndent(),
                )
                v2.close()

                // Runs the migration AND validates the migrated schema against the exported 3.json
                // (identity hash + table shape) — proving the ALTER matches what Room expects.
                val v3 = helper.runMigrationsAndValidate(version = 3, migrations = listOf(MIGRATION_2_3))

                v3.withStatement(
                    "SELECT title, normalizationGainDb FROM books WHERE id = 'book1'",
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Test Book"
                    statement.isNull(1) shouldBe true
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
