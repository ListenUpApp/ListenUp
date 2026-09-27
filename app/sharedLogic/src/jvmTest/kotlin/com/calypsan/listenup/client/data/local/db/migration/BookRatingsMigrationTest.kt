package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_8_9
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v8 → v9: the `book_ratings` table arrives.
 *
 * A pure additive migration — `CREATE TABLE` plus two indices, copied verbatim from the exported
 * `schemas/…/9.json` — so the interesting assertions are that `runMigrationsAndValidate` accepts
 * the hand-written DDL as identical to a fresh v9 install, that existing rows in other tables
 * survive untouched (the migration policy in `ListenUpDatabase`: this database holds the unsynced
 * outbox), and that the new table is immediately writable.
 */
class BookRatingsMigrationTest :
    FunSpec({
        test("MIGRATION_8_9 adds book_ratings, keeps every existing row, and validates against 9.json") {
            val helper = createMigrationTestHelper()
            try {
                val v8 = helper.createDatabase(version = 8)
                v8.execSQL(
                    "INSERT INTO book_moods (bookId, moodId, syncId, createdAt, revision) " +
                        "VALUES ('b1', 'm1', 's1', 1, 0)",
                )
                v8.close()

                val v9 = helper.runMigrationsAndValidate(version = 9, migrations = listOf(MIGRATION_8_9))

                v9.withStatement("SELECT COUNT(*) FROM book_moods") { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 1L
                }

                v9.execSQL(
                    "INSERT INTO book_ratings (bookId, userId, syncId, halfStars, note, ratedAt, updatedAt, revision) " +
                        "VALUES ('b1', 'u1', 'r1', 7, NULL, 1, 1, 0)",
                )
                v9.withStatement(
                    "SELECT halfStars, note FROM book_ratings WHERE bookId = 'b1' AND userId = 'u1'",
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 7L
                    statement.isNull(1) shouldBe true
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
