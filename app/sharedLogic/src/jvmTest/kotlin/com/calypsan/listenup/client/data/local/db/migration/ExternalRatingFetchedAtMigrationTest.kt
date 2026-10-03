package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_13_14
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v13 → v14: `book_external_ratings` gains `fetchedAt`, so Book Detail can say how fresh an
 * outside score is. The column is nullable, and rows mirrored before it have no time, so the migration
 * also rewinds that domain's cursor and the next catch-up re-pulls every row with its time. Every rating
 * survives, and every other domain's cursor is left alone.
 */
class ExternalRatingFetchedAtMigrationTest :
    FunSpec({
        test("MIGRATION_13_14 adds fetchedAt, keeps every rating, rewinds only this domain's cursor, and validates against 14.json") {
            val helper = createMigrationTestHelper()
            try {
                val v13 = helper.createDatabase(version = 13)
                v13.execSQL(
                    "INSERT INTO book_external_ratings (bookId, source, syncId, average, count, enabled, revision) " +
                        "VALUES ('b1', 'AUDIBLE', 'e1', 4.7, 1007, 1, 490)",
                )
                v13.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('book_external_ratings', 500)")
                v13.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('book_ratings', 700)")
                v13.close()

                val v14 = helper.runMigrationsAndValidate(version = 14, migrations = listOf(MIGRATION_13_14))

                v14.withStatement("SELECT source, average, fetchedAt FROM book_external_ratings") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "AUDIBLE"
                    statement.getDouble(1) shouldBe 4.7
                    statement.isNull(2) shouldBe true
                    statement.step() shouldBe false
                }
                v14.withStatement("SELECT domainName, revision FROM sync_cursor ORDER BY domainName") { statement ->
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
