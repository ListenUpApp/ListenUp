package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_15_16
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v15 → v16: `series` gains the hierarchy columns (#962). An `ADD COLUMN` pair plus an
 * index, so the assertions are that the hand-written DDL validates against the exported `16.json`
 * and that an existing series survives as a root — and a rewound `series` sync cursor, so the next
 * catch-up re-pulls the parents an older build dropped.
 */
class SeriesHierarchyMigrationTest :
    FunSpec({
        test("MIGRATION_15_16 adds parentId and parentPosition, keeps every series as a root, and validates against 16.json") {
            val helper = createMigrationTestHelper()
            try {
                val v15 = helper.createDatabase(version = 15)
                v15.execSQL(
                    "INSERT INTO series (id, name, revision, createdAt, updatedAt) VALUES ('s1', 'Mistborn', 7, 1, 2)",
                )
                v15.close()

                val v16 = helper.runMigrationsAndValidate(version = 16, migrations = listOf(MIGRATION_15_16))

                v16.withStatement("SELECT name, revision, parentId, parentPosition FROM series") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Mistborn"
                    statement.getLong(1) shouldBe 7L
                    statement.isNull(2) shouldBe true
                    statement.isNull(3) shouldBe true
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }

        test("MIGRATION_15_16 rewinds the series sync cursor and leaves every other domain's alone") {
            val helper = createMigrationTestHelper()
            try {
                val v15 = helper.createDatabase(version = 15)
                v15.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('series', 42)")
                v15.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 99)")
                v15.close()

                val v16 = helper.runMigrationsAndValidate(version = 16, migrations = listOf(MIGRATION_15_16))

                v16.withStatement("SELECT domainName, revision FROM sync_cursor ORDER BY domainName") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "books"
                    statement.getLong(1) shouldBe 99L
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
