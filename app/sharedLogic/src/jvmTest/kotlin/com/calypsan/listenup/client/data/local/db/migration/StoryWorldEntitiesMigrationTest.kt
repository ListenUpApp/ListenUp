package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_19_20
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * v19 → v20: the `entities` mirror (Story World), the two Story World flags on `users` and
 * `admin_user_roster` with the server's defaults (contribute on, curate off), and the outbox's
 * `mayHaveLanded` flag. Pure CREATE/ADD COLUMN — every existing row, the outbox included, survives.
 */
class StoryWorldEntitiesMigrationTest :
    FunSpec({
        test("MIGRATION_19_20 adds entities, the Story World flags and mayHaveLanded, keeping every row; matches 20.json") {
            val helper = createMigrationTestHelper()
            try {
                val v19 = helper.createDatabase(version = 19)
                v19.execSQL(
                    "INSERT INTO users (id, email, displayName, isRoot, createdAt, updatedAt, canEdit, canCurateLibrary) " +
                        "VALUES ('u1', 'a@b.c', 'A', 0, 0, 0, 1, 1)",
                )
                v19.execSQL(
                    "INSERT INTO admin_user_roster (id, email, displayName, role, status, canEdit, canCurateLibrary, " +
                        "accountCreatedAt, revision, deletedAt) VALUES ('u1', 'a@b.c', 'A', 'MEMBER', 'ACTIVE', 1, 1, 0, 1, NULL)",
                )
                v19.execSQL(
                    "INSERT INTO pending_operation (clientOpId, domainName, entityId, opType, payload, enqueuedAt, " +
                        "lastAttemptAt, failureCount, lastError, ownerUserId) " +
                        "VALUES ('op1', 'series', 's1', 'update', '{}', 1, NULL, 0, NULL, 'u1')",
                )
                v19.close()

                val v20 = helper.runMigrationsAndValidate(version = 20, migrations = listOf(MIGRATION_19_20))

                v20.withStatement(
                    "SELECT canContributeStoryWorld, canCurateStoryWorld, canCurateLibrary FROM users WHERE id = 'u1'",
                ) { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 1
                    row.getLong(1) shouldBe 0
                    row.getLong(2) shouldBe 1
                }
                v20.withStatement(
                    "SELECT canContributeStoryWorld, canCurateStoryWorld FROM admin_user_roster WHERE id = 'u1'",
                ) { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 1
                    row.getLong(1) shouldBe 0
                }
                v20.withStatement("SELECT COUNT(*) FROM entities") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 0
                }
                v20.withStatement("SELECT COUNT(*), MAX(mayHaveLanded) FROM pending_operation") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 1
                    row.getLong(1) shouldBe 0
                }
            } finally {
                helper.close()
            }
        }
    })
