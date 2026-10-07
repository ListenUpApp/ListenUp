package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_18_19
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * v18 → v19: the `entities` mirror (Story World) and the signed-in user's two Story World flags, with the
 * server's defaults (contribute on, curate off). Pure CREATE/ADD COLUMN — every existing row, the outbox
 * included, survives.
 */
class StoryWorldEntitiesMigrationTest :
    FunSpec({
        test("MIGRATION_18_19 adds entities and the Story World flags, keeps users and the outbox, and validates against 19.json") {
            val helper = createMigrationTestHelper()
            try {
                val v18 = helper.createDatabase(version = 18)
                v18.execSQL(
                    "INSERT INTO users (id, email, displayName, isRoot, createdAt, updatedAt, canEdit) " +
                        "VALUES ('u1', 'a@b.c', 'A', 0, 0, 0, 1)",
                )
                v18.execSQL(
                    "INSERT INTO pending_operation (clientOpId, domainName, entityId, opType, payload, enqueuedAt, " +
                        "lastAttemptAt, failureCount, lastError, ownerUserId) " +
                        "VALUES ('op1', 'series', 's1', 'update', '{}', 1, NULL, 0, NULL, 'u1')",
                )
                v18.close()

                val v19 = helper.runMigrationsAndValidate(version = 19, migrations = listOf(MIGRATION_18_19))

                v19.withStatement("SELECT canContributeStoryWorld, canCurateStoryWorld FROM users WHERE id = 'u1'") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 1
                    row.getLong(1) shouldBe 0
                }
                v19.withStatement("SELECT COUNT(*) FROM entities") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 0
                }
                v19.withStatement("SELECT COUNT(*) FROM pending_operation") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 1
                }
            } finally {
                helper.close()
            }
        }
    })
