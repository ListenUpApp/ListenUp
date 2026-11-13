package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_29_30
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v29 → v30: `book_readership.currentPositionMs` and `lastPlayedAtMs` appear empty, and every cached
 * reader is kept as it was. Empty is honest: the next presence ping refills the mirror with both.
 */
class ReadershipPositionMigrationTest :
    FunSpec({
        test("MIGRATION_29_30 adds an empty position and last play, keeps every reader, and validates against 30.json") {
            val helper = createMigrationTestHelper()
            try {
                val v29 = helper.createDatabase(version = 29)
                v29.execSQL(
                    "INSERT INTO book_readership (bookId, userId, displayName, avatarType, currentProgressPct, " +
                        "finishesJson, observedAt, hardcoverFinishesJson, finishesAlsoOnHardcoverJson) " +
                        "VALUES ('b1', 'u1', 'Maya', 'auto', 42, '', 5, '', '')",
                )
                v29.close()

                val v30 = helper.runMigrationsAndValidate(version = 30, migrations = listOf(MIGRATION_29_30))

                v30.withStatement(
                    "SELECT displayName, currentProgressPct, currentPositionMs, lastPlayedAtMs FROM book_readership",
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Maya"
                    statement.getLong(1) shouldBe 42
                    statement.isNull(2) shouldBe true
                    statement.isNull(3) shouldBe true
                }
            } finally {
                helper.close()
            }
        }
    })
