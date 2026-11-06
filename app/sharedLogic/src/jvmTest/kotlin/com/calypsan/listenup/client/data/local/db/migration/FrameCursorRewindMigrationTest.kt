package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_27_28
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v27 → v28: no table changes, but the `contributors` and `public_profiles` cursors are removed so the next
 * catch-up re-pulls both domains and every photo frame set while the device ran an older app arrives. Every other
 * cursor — `playback_positions` included — and the rows themselves are left alone.
 */
class FrameCursorRewindMigrationTest :
    FunSpec({
        test("MIGRATION_27_28 keeps both rows, rewinds only the contributor and profile cursors, and validates against 28.json") {
            val helper = createMigrationTestHelper()
            try {
                val v27 = helper.createDatabase(version = 27)
                v27.execSQL(
                    "INSERT INTO contributors (id, name, imagePath, revision, createdAt, updatedAt) " +
                        "VALUES ('c1', 'Ray Porter', 'contributors/abc.jpg', 3, 1, 1)",
                )
                v27.execSQL(
                    "INSERT INTO public_profiles (id, displayName, avatarType, totalSecondsAllTime, totalSecondsLast7Days, " +
                        "totalSecondsLast30Days, totalSecondsLast365Days, booksFinished, currentStreakDays, longestStreakDays, revision) " +
                        "VALUES ('u1', 'Ada', 'image', 0, 0, 0, 0, 0, 0, 0, 5)",
                )
                v27.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('contributors', 700)")
                v27.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('public_profiles', 300)")
                v27.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('playback_positions', 900)")
                v27.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 500)")
                v27.close()

                val v28 = helper.runMigrationsAndValidate(version = 28, migrations = listOf(MIGRATION_27_28))

                v28.withStatement("SELECT id, revision FROM contributors") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "c1"
                    statement.getLong(1) shouldBe 3
                    statement.step() shouldBe false
                }
                v28.withStatement("SELECT id, revision FROM public_profiles") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "u1"
                    statement.getLong(1) shouldBe 5
                    statement.step() shouldBe false
                }
                v28.withStatement("SELECT domainName FROM sync_cursor ORDER BY domainName") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "books"
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "playback_positions"
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
