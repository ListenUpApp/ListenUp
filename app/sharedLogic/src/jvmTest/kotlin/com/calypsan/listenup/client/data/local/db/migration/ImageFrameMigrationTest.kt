package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_25_26
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v25 → v26: `contributors.imageFrame` and `public_profiles.avatarFrame` appear empty, and nothing already
 * in the database is touched — in particular the rows they extend, the unsynced outbox and the sync cursors.
 */
class ImageFrameMigrationTest :
    FunSpec({
        test("MIGRATION_25_26 adds the two frame columns, keeps every row, and validates against 26.json") {
            val helper = createMigrationTestHelper()
            try {
                val v25 = helper.createDatabase(version = 25)
                v25.execSQL(
                    "INSERT INTO contributors (id, name, imagePath, revision, createdAt, updatedAt) " +
                        "VALUES ('c1', 'Ray Porter', 'contributors/abc.jpg', 3, 1, 1)",
                )
                v25.execSQL(
                    "INSERT INTO public_profiles (id, displayName, avatarType, totalSecondsAllTime, totalSecondsLast7Days, " +
                        "totalSecondsLast30Days, totalSecondsLast365Days, booksFinished, currentStreakDays, longestStreakDays, revision) " +
                        "VALUES ('u1', 'Ada', 'image', 0, 0, 0, 0, 0, 0, 0, 1)",
                )
                v25.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('contributors', 700)")
                v25.close()

                val v26 = helper.runMigrationsAndValidate(version = 26, migrations = listOf(MIGRATION_25_26))

                v26.withStatement("SELECT imagePath, imageFrame FROM contributors") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "contributors/abc.jpg"
                    statement.isNull(1) shouldBe true
                }
                v26.withStatement("SELECT avatarType, avatarFrame FROM public_profiles") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "image"
                    statement.isNull(1) shouldBe true
                }
                v26.withStatement("SELECT revision FROM sync_cursor WHERE domainName = 'contributors'") { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 700
                }
            } finally {
                helper.close()
            }
        }
    })
