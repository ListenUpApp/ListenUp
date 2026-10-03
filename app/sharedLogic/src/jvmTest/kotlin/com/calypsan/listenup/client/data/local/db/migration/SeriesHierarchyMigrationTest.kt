package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_13_14
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v13 → v14: `series` gains the hierarchy columns (#962). A pure `ADD COLUMN` pair plus
 * an index, so the assertions are that the hand-written DDL validates against the exported
 * `14.json` and that an existing series survives as a root.
 */
class SeriesHierarchyMigrationTest :
    FunSpec({
        test("MIGRATION_13_14 adds parentId and parentPosition, keeps every series as a root, and validates against 14.json") {
            val helper = createMigrationTestHelper()
            try {
                val v13 = helper.createDatabase(version = 13)
                v13.execSQL(
                    "INSERT INTO series (id, name, revision, createdAt, updatedAt) VALUES ('s1', 'Mistborn', 7, 1, 2)",
                )
                v13.close()

                val v14 = helper.runMigrationsAndValidate(version = 14, migrations = listOf(MIGRATION_13_14))

                v14.withStatement("SELECT name, revision, parentId, parentPosition FROM series") { statement ->
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
    })
