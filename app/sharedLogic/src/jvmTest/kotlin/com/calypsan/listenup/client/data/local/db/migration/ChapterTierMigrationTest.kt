package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_7_8
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v7 → v8: the two-tier chapter-grouping columns arrive.
 *
 * Four `ADD COLUMN`s, so the assertions that earn their place are that
 * `runMigrationsAndValidate` accepts the hand-written DDL as identical to a fresh v8 install, and
 * that **the unsynced outbox survives**. That second one is the whole reason this database has a
 * non-destructive migration policy: a queued `pending_operations_v2` row is an edit the user made
 * that has never reached the server, so "it re-syncs from the server" is exactly the story that
 * does not cover it.
 */
class ChapterTierMigrationTest :
    FunSpec({
        test("MIGRATION_7_8 adds the tier columns and preserves the unsynced outbox") {
            val helper = createMigrationTestHelper()
            try {
                val v7 = helper.createDatabase(version = 7)
                v7.execSQL(
                    """
                    INSERT INTO pending_operation
                        (clientOpId, domainName, entityId, opType, payload, enqueuedAt, failureCount, ownerUserId)
                    VALUES ('op1', 'books', 'book1', 'update', '{}', 100, 0, 'user1')
                    """.trimIndent(),
                )
                v7.close()

                val v8 = helper.runMigrationsAndValidate(version = 8, migrations = listOf(MIGRATION_7_8))

                withClue("an edit that never reached the server cannot be re-fetched from it") {
                    v8.withStatement("SELECT entityId FROM pending_operation WHERE clientOpId = 'op1'") { statement ->
                        statement.step() shouldBe true
                        statement.getText(0) shouldBe "book1"
                        statement.step() shouldBe false
                    }
                }
            } finally {
                helper.close()
            }
        }

        test("the new columns are immediately writable, and default to unnamed") {
            val helper = createMigrationTestHelper()
            try {
                val v7 = helper.createDatabase(version = 7)
                v7.execSQL(
                    """
                    INSERT INTO books
                        (id, libraryId, folderId, title, totalDuration, abridged, revision,
                         hasScanWarning, createdAt, updatedAt)
                    VALUES ('b1', 'lib1', 'f1', 'The Way of Kings', 1000, 0, 1, 0, 100, 100)
                    """.trimIndent(),
                )
                v7.execSQL(
                    """
                    INSERT INTO chapters (id, bookId, title, duration, startTime)
                    VALUES ('c1', 'b1', 'Prologue', 100, 0)
                    """.trimIndent(),
                )
                v7.close()

                val v8 = helper.runMigrationsAndValidate(version = 8, migrations = listOf(MIGRATION_7_8))

                withClue("a book that predates tiers names neither, and null is how that is spelled") {
                    v8.withStatement("SELECT bookTierLabel, partTierLabel FROM books WHERE id = 'b1'") { statement ->
                        statement.step() shouldBe true
                        statement.isNull(0) shouldBe true
                        statement.isNull(1) shouldBe true
                    }
                }

                v8.execSQL("UPDATE books SET bookTierLabel = 'Volume', partTierLabel = 'Sequence' WHERE id = 'b1'")
                v8.execSQL("UPDATE chapters SET partTitle = 'Part One', bookTitle = 'Book One' WHERE id = 'c1'")

                v8.withStatement("SELECT bookTierLabel FROM books WHERE id = 'b1'") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Volume"
                }
                v8.withStatement("SELECT partTitle, bookTitle FROM chapters WHERE id = 'c1'") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Part One"
                    statement.getText(1) shouldBe "Book One"
                }
            } finally {
                helper.close()
            }
        }
    })
