package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_10_11
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v10 → v11: outside ratings an older build could not read come back.
 *
 * PR-2 builds decoded a `source` they did not know (HARDCOVER, GOODREADS) as `UNKNOWN` and hid the
 * row. This build knows them, but the cursored pull never re-sends an unchanged row — so the
 * migration drops the `UNKNOWN` rows and rewinds the `book_external_ratings` cursor. A missing
 * cursor is `since = 0` in `SyncCatchUpClient.catchUp`, so the next catch-up re-pulls every row,
 * decoded. Known-source rows and every other domain's cursor are left alone.
 */
class ExternalRatingSourceRepairMigrationTest :
    FunSpec({
        test("MIGRATION_10_11 drops UNKNOWN-source rows and rewinds only the book_external_ratings cursor") {
            val helper = createMigrationTestHelper()
            try {
                val v10 = helper.createDatabase(version = 10)
                v10.execSQL(
                    "INSERT INTO book_external_ratings (bookId, source, syncId, average, count, enabled, revision) " +
                        "VALUES ('b1', 'UNKNOWN', 'e1', 4.2, 100000, 1, 480)",
                )
                v10.execSQL(
                    "INSERT INTO book_external_ratings (bookId, source, syncId, average, count, enabled, revision) " +
                        "VALUES ('b1', 'AUDIBLE', 'e2', 4.7, 1007, 1, 490)",
                )
                v10.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('book_external_ratings', 500)")
                v10.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('book_ratings', 700)")
                v10.close()

                val v11 = helper.runMigrationsAndValidate(version = 11, migrations = listOf(MIGRATION_10_11))

                v11.withStatement("SELECT source FROM book_external_ratings ORDER BY source") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "AUDIBLE"
                    statement.step() shouldBe false
                }
                v11.withStatement("SELECT domainName, revision FROM sync_cursor ORDER BY domainName") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "book_ratings"
                    statement.getLong(1) shouldBe 700L
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
