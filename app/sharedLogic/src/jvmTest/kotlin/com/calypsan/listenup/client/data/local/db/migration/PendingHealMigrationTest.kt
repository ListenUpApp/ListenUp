package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_28_29
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v28 → v29: the `pending_heal` table appears, keyed by (domainName, entityId), and the unsynced
 * outbox already in the database is left alone.
 */
class PendingHealMigrationTest :
    FunSpec({
        test("MIGRATION_28_29 creates pending_heal, leaves the outbox alone, and validates against 29.json") {
            val helper = createMigrationTestHelper()
            try {
                val v28 = helper.createDatabase(version = 28)
                v28.execSQL(
                    "INSERT INTO pending_operation (clientOpId, domainName, entityId, opType, payload, enqueuedAt, " +
                        "lastAttemptAt, failureCount, lastError, ownerUserId, mayHaveLanded) " +
                        "VALUES ('op1', 'tags', 't1', 'update', '{}', 5, NULL, 6, 'NOT_FOUND', 'u1', 0)",
                )
                v28.close()

                val v29 = helper.runMigrationsAndValidate(version = 29, migrations = listOf(MIGRATION_28_29))

                v29.withStatement("SELECT clientOpId, failureCount FROM pending_operation") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "op1"
                    statement.getLong(1) shouldBe 6
                    statement.step() shouldBe false
                }
                v29.execSQL("INSERT INTO pending_heal (domainName, entityId, requestId) VALUES ('tags', 't1', 'r1')")
                // One row per entity: a second request replaces the first.
                v29.execSQL("INSERT OR REPLACE INTO pending_heal (domainName, entityId, requestId) VALUES ('tags', 't1', 'r2')")
                v29.withStatement("SELECT domainName, entityId, requestId FROM pending_heal") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "tags"
                    statement.getText(1) shouldBe "t1"
                    statement.getText(2) shouldBe "r2"
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
