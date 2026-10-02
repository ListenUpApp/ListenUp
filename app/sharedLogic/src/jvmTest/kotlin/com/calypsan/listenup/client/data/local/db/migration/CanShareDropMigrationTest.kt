package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_12_13
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Validates v12 → v13: the inert "Can share" permission leaves the local mirror.
 *
 * Two column drops — `users.canShare` and `admin_user_roster.canShare` — validated against the
 * exported `13.json`. The substance is that nothing else moves: the signed-in user and every roster
 * row survive with their other flags intact, because a migration that loses the users row signs the
 * listener out of an offline-first app.
 */
class CanShareDropMigrationTest :
    FunSpec({
        test("MIGRATION_12_13 drops canShare from users and admin_user_roster and keeps every row") {
            val helper = createMigrationTestHelper()
            try {
                val v12 = helper.createDatabase(version = 12)
                v12.execSQL(
                    "INSERT INTO users (id, email, displayName, isRoot, createdAt, updatedAt, canEdit, canShare) " +
                        "VALUES ('user1', 'reader@example.com', 'Reader', 0, 1000, 2000, 0, 0)",
                )
                v12.execSQL(
                    "INSERT INTO admin_user_roster " +
                        "(id, email, displayName, role, status, canShare, canEdit, accountCreatedAt, revision) " +
                        "VALUES ('user1', 'reader@example.com', 'Reader', 'MEMBER', 'ACTIVE', 0, 0, 1000, 7)",
                )
                v12.close()

                val v13 = helper.runMigrationsAndValidate(version = 13, migrations = listOf(MIGRATION_12_13))

                listOf("users", "admin_user_roster").forEach { table ->
                    val columns = mutableListOf<String>()
                    v13.withStatement("PRAGMA table_info('$table')") { statement ->
                        while (statement.step()) columns += statement.getText(1)
                    }
                    columns shouldNotContain "canShare"
                }
                v13.withStatement("SELECT displayName, isRoot, canEdit, updatedAt FROM users") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Reader"
                    statement.getLong(1) shouldBe 0L
                    statement.getLong(2) shouldBe 0L
                    statement.getLong(3) shouldBe 2000L
                    statement.step() shouldBe false
                }
                v13.withStatement("SELECT email, role, canEdit, revision FROM admin_user_roster") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "reader@example.com"
                    statement.getText(1) shouldBe "MEMBER"
                    statement.getLong(2) shouldBe 0L
                    statement.getLong(3) shouldBe 7L
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
