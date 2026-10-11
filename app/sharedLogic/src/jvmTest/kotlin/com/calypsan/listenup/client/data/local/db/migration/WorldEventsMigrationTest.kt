package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_21_22
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * v21 → v22: the `world_events` and `world_event_mentions` mirrors (Story World PR B). Pure CREATE — every
 * existing row, the outbox and the entities mirror included, survives.
 */
class WorldEventsMigrationTest :
    FunSpec({
        test("MIGRATION_21_22 adds the event tables, keeping every row; matches 22.json") {
            val helper = createMigrationTestHelper()
            try {
                val v21 = helper.createDatabase(version = 21)
                v21.execSQL(
                    "INSERT INTO entities (id, kind, name, revision, createdAt, updatedAt) VALUES ('e1', 'CHARACTER', 'Darrow', 1, 0, 0)",
                )
                v21.execSQL(
                    "INSERT INTO pending_operation (clientOpId, domainName, entityId, opType, payload, enqueuedAt, " +
                        "lastAttemptAt, failureCount, lastError, ownerUserId) " +
                        "VALUES ('op1', 'entities', 'e1', 'upsert', '{}', 1, NULL, 0, NULL, 'u1')",
                )
                v21.close()

                val v22 = helper.runMigrationsAndValidate(version = 22, migrations = listOf(MIGRATION_21_22))

                v22.withStatement("SELECT COUNT(*) FROM world_events") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 0
                }
                v22.withStatement("SELECT COUNT(*) FROM world_event_mentions") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 0
                }
                v22.withStatement("SELECT name FROM entities WHERE id = 'e1'") { row ->
                    row.step() shouldBe true
                    row.getText(0) shouldBe "Darrow"
                }
                v22.withStatement("SELECT COUNT(*) FROM pending_operation") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 1
                }
            } finally {
                helper.close()
            }
        }
    })
