package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_9_10
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v9 → v10: the `book_external_ratings` table arrives.
 *
 * A pure additive migration — `CREATE TABLE` plus two indices, copied verbatim from the exported
 * `schemas/…/10.json` — so the interesting assertions are that `runMigrationsAndValidate` accepts
 * the hand-written DDL as identical to a fresh v10 install, that existing rows in other tables
 * survive untouched (the migration policy in `ListenUpDatabase`: this database holds the unsynced
 * outbox), and that the new table is immediately writable — including a row whose `source` is a
 * literal this client build doesn't recognise ("UNKNOWN" and beyond), which must store cleanly
 * rather than fail the insert.
 */
class BookExternalRatingsMigrationTest :
    FunSpec({
        test("MIGRATION_9_10 adds book_external_ratings, keeps every existing row, and validates against 10.json") {
            val helper = createMigrationTestHelper()
            try {
                val v9 = helper.createDatabase(version = 9)
                v9.execSQL(
                    "INSERT INTO book_ratings (bookId, userId, syncId, halfStars, note, ratedAt, updatedAt, revision) " +
                        "VALUES ('b1', 'u1', 'r1', 7, NULL, 1, 1, 0)",
                )
                v9.close()

                val v10 = helper.runMigrationsAndValidate(version = 10, migrations = listOf(MIGRATION_9_10))

                v10.withStatement("SELECT COUNT(*) FROM book_ratings") { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 1L
                }

                v10.execSQL(
                    "INSERT INTO book_external_ratings " +
                        "(bookId, source, syncId, average, count, enabled, revision) " +
                        "VALUES ('b1', 'AUDIBLE', 'e1', 4.4, 812, 1, 0)",
                )
                v10.withStatement(
                    "SELECT average, count, enabled FROM book_external_ratings WHERE bookId = 'b1' AND source = 'AUDIBLE'",
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getDouble(0) shouldBe 4.4
                    statement.getLong(1) shouldBe 812L
                    statement.getLong(2) shouldBe 1L
                    statement.step() shouldBe false
                }

                // A source this client doesn't recognise must still store — the client-side
                // ExternalRatingSourceSerializer falls back to UNKNOWN rather than failing, and
                // this substrate mirrors it verbatim (see BookExternalRatingEntity's KDoc).
                v10.execSQL(
                    "INSERT INTO book_external_ratings " +
                        "(bookId, source, syncId, average, count, enabled, revision) " +
                        "VALUES ('b2', 'UNKNOWN', 'e2', 3.9, 40, 1, 0)",
                )
                v10.withStatement(
                    "SELECT average FROM book_external_ratings WHERE bookId = 'b2' AND source = 'UNKNOWN'",
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getDouble(0) shouldBe 3.9
                }
            } finally {
                helper.close()
            }
        }
    })
