package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_11_12
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v11 → v12: the readership cache gains the reads each reader logged on Hardcover (#601 B3).
 * A pure `ADD COLUMN` with an empty default — so the interesting assertions are that the hand-written
 * DDL validates against the exported `12.json`, and that a cached reader survives with no Hardcover
 * reads until the next refresh brings them.
 */
class ReadershipHardcoverMigrationTest :
    FunSpec({
        test("MIGRATION_11_12 adds hardcoverFinishesJson, keeps every cached reader, and validates against 12.json") {
            val helper = createMigrationTestHelper()
            try {
                val v11 = helper.createDatabase(version = 11)
                v11.execSQL(
                    "INSERT INTO book_readership " +
                        "(bookId, userId, displayName, avatarType, currentProgressPct, finishesJson, observedAt) " +
                        "VALUES ('b1', 'u1', 'Ann', 'auto', NULL, '300,100', 5)",
                )
                v11.close()

                val v12 = helper.runMigrationsAndValidate(version = 12, migrations = listOf(MIGRATION_11_12))

                v12.withStatement("SELECT finishesJson, hardcoverFinishesJson FROM book_readership") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "300,100"
                    statement.getText(1) shouldBe ""
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
