package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_30_31
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v30 → v31: `book_contributors.ordinal` appears as 0 on every existing credit, and storage order (rowid),
 * the tie-break every reader uses, is untouched, so a book keeps the author order it showed before.
 */
class ContributorOrdinalMigrationTest :
    FunSpec({
        test("MIGRATION_30_31 adds a zero ordinal, keeps every credit in storage order, and validates against 31.json") {
            val helper = createMigrationTestHelper()
            try {
                val v30 = helper.createDatabase(version = 30)
                v30.execSQL("INSERT INTO book_contributors (bookId, contributorId, role) VALUES ('b1', 'jay', 'author')")
                v30.execSQL("INSERT INTO book_contributors (bookId, contributorId, role) VALUES ('b1', 'bon', 'author')")
                v30.close()

                val v31 = helper.runMigrationsAndValidate(version = 31, migrations = listOf(MIGRATION_30_31))

                v31.withStatement("SELECT contributorId, ordinal FROM book_contributors ORDER BY ordinal, rowid") { s ->
                    s.step() shouldBe true
                    s.getText(0) shouldBe "jay"
                    s.getLong(1) shouldBe 0
                    s.step() shouldBe true
                    s.getText(0) shouldBe "bon"
                    s.getLong(1) shouldBe 0
                }
            } finally {
                helper.close()
            }
        }
    })
