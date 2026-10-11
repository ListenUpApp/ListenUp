package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_22_23
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * v22 → v23: `book_ratings.source`. Every existing rating is a ListenUp rating, and the domain's cursor
 * goes, so ratings a newer server imported from Hardcover before this client knew the field re-pull with it.
 */
class BookRatingSourceMigrationTest :
    FunSpec({
        test("MIGRATION_22_23 marks every rating ListenUp's and rewinds the ratings cursor; matches 23.json") {
            val helper = createMigrationTestHelper()
            try {
                val v22 = helper.createDatabase(version = 22)
                v22.execSQL(
                    "INSERT INTO book_ratings (bookId, userId, syncId, halfStars, note, ratedAt, updatedAt, revision, deletedAt) " +
                        "VALUES ('b1', 'u1', 'r1', 7, NULL, 1, 1, 4, NULL)",
                )
                v22.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('book_ratings', 400)")
                v22.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 700)")
                v22.close()

                val v23 = helper.runMigrationsAndValidate(version = 23, migrations = listOf(MIGRATION_22_23))

                v23.withStatement("SELECT halfStars, source FROM book_ratings WHERE syncId = 'r1'") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 7
                    row.getText(1) shouldBe "LISTENUP"
                }
                v23.withStatement("SELECT COUNT(*) FROM sync_cursor WHERE domainName = 'book_ratings'") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 0
                }
                v23.withStatement("SELECT revision FROM sync_cursor WHERE domainName = 'books'") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 700
                }
            } finally {
                helper.close()
            }
        }
    })
