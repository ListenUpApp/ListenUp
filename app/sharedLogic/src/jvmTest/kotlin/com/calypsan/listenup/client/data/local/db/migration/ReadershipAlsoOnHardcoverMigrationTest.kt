package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_14_15
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v14 → v15: which of a reader's finishes were also logged on Hardcover moves out of the
 * `:hardcover` suffix #1567 wrote into `book_readership.finishesJson` and into its own
 * `finishesAlsoOnHardcoverJson` column. A cached reader keeps every finish, keeps the flag, and no
 * suffix survives in `finishesJson`.
 */
class ReadershipAlsoOnHardcoverMigrationTest :
    FunSpec({
        test("MIGRATION_14_15 moves :hardcover suffixes into finishesAlsoOnHardcoverJson and validates against 15.json") {
            val helper = createMigrationTestHelper()
            try {
                val v14 = helper.createDatabase(version = 14)
                listOf(
                    "u1" to "900:hardcover,300",
                    "u2" to "300,100",
                    "u3" to "",
                    "u4" to "5:hardcover,4:hardcover,3",
                ).forEach { (userId, finishes) ->
                    v14.execSQL(
                        "INSERT INTO book_readership " +
                            "(bookId, userId, displayName, avatarType, currentProgressPct, finishesJson, observedAt, " +
                            "hardcoverFinishesJson) " +
                            "VALUES ('b1', '$userId', 'Ann', 'auto', NULL, '$finishes', 5, '700')",
                    )
                }
                v14.close()

                val v15 = helper.runMigrationsAndValidate(version = 15, migrations = listOf(MIGRATION_14_15))

                v15.withStatement(
                    "SELECT userId, finishesJson, finishesAlsoOnHardcoverJson, hardcoverFinishesJson " +
                        "FROM book_readership ORDER BY userId",
                ) { statement ->
                    listOf(
                        Triple("u1", "900,300", "900"),
                        Triple("u2", "300,100", ""),
                        Triple("u3", "", ""),
                        Triple("u4", "5,4,3", "5,4"),
                    ).forEach { (userId, finishes, alsoOnHardcover) ->
                        statement.step() shouldBe true
                        statement.getText(0) shouldBe userId
                        statement.getText(1) shouldBe finishes
                        statement.getText(2) shouldBe alsoOnHardcover
                        statement.getText(3) shouldBe "700"
                    }
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
