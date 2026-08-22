package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_6_7
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v6 → v7: the `notifications` inbox table arrives.
 *
 * A pure additive migration — `CREATE TABLE` plus two indices, copied verbatim from the exported
 * `schemas/…/7.json` — so the interesting assertions are that `runMigrationsAndValidate` accepts
 * the hand-written DDL as identical to a fresh v7 install, that existing rows in other tables
 * survive untouched (the migration policy in `ListenUpDatabase`: this database holds the unsynced
 * outbox), and that the new table is immediately writable.
 */
class NotificationsTableMigrationTest :
    FunSpec({
        test("MIGRATION_6_7 creates the notifications table and preserves existing rows") {
            val helper = createMigrationTestHelper()
            try {
                val v6 = helper.createDatabase(version = 6)
                // A stand-in for the data the policy exists to protect: any pre-existing row
                // must ride through the additive migration untouched.
                v6.execSQL(
                    """
                    INSERT INTO tags (id, name, slug, updatedAt, revision)
                    VALUES ('t1', 'Epic', 'epic', 100, 1)
                    """.trimIndent(),
                )
                v6.close()

                val v7 = helper.runMigrationsAndValidate(version = 7, migrations = listOf(MIGRATION_6_7))

                v7.withStatement("SELECT name FROM tags WHERE id = 't1'") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Epic"
                    statement.step() shouldBe false
                }

                v7.execSQL(
                    """
                    INSERT INTO notifications
                        (id, type, eventJson, createdAt, updatedAt, readAt, revision, deletedAt)
                    VALUES ('n1', 'registration.pending', '{}', 100, 100, NULL, 1, NULL)
                    """.trimIndent(),
                )
                v7.withStatement(
                    "SELECT COUNT(*) FROM notifications WHERE deletedAt IS NULL AND readAt IS NULL",
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 1L
                }
            } finally {
                helper.close()
            }
        }
    })
