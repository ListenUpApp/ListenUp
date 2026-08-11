package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_3_4
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v3 → v4: the per-user permission flags the client used to drop (#1270).
 *
 * Three pure `ADD COLUMN`s — `admin_user_roster.canEdit`, `users.canEdit`, `users.canShare` — plus
 * Room's full schema validation against the exported `4.json`.
 *
 * The `DEFAULT 1` assertions are the substance, not boilerplate. Every one of these flags gates a
 * capability, and the default is what decides how a row written before the column existed behaves.
 * Defaulting to 0 would silently revoke edit and share rights from every existing user on upgrade —
 * a permissions change nobody asked for, arriving as a migration. `1` matches both the server column
 * default and `UserPermissions`' own default, so the upgrade is a no-op until real values sync down.
 *
 * Data survival matters for the same reason it does in [VolumeBoostMigrationTest]: the local DB
 * holds the unsynced outbox, so a migration that drops rows loses writes that never reached the
 * server.
 */
class PermissionFlagsMigrationTest :
    FunSpec({
        test("MIGRATION_3_4 preserves roster rows and grants canEdit by default") {
            val helper = createMigrationTestHelper()
            try {
                val v3 = helper.createDatabase(version = 3)
                v3.execSQL(
                    """
                    INSERT INTO admin_user_roster
                        (id, email, displayName, role, status, canShare, accountCreatedAt, revision)
                    VALUES ('user1', 'reader@example.com', 'Reader', 'MEMBER', 'ACTIVE', 0, 1000, 7)
                    """.trimIndent(),
                )
                v3.close()

                val v4 = helper.runMigrationsAndValidate(version = 4, migrations = listOf(MIGRATION_3_4))

                v4.withStatement(
                    """
                    SELECT email, role, canShare, canEdit, revision
                    FROM admin_user_roster WHERE id = 'user1'
                    """.trimIndent(),
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "reader@example.com"
                    statement.getText(1) shouldBe "MEMBER"
                    // canShare = 0 survives: the migration must not reset a flag an admin already set.
                    statement.getLong(2) shouldBe 0L
                    statement.getLong(3) shouldBe 1L
                    statement.getLong(4) shouldBe 7L
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }

        test("MIGRATION_3_4 preserves the signed-in user and grants both flags by default") {
            val helper = createMigrationTestHelper()
            try {
                val v3 = helper.createDatabase(version = 3)
                v3.execSQL(
                    """
                    INSERT INTO users (id, email, displayName, isRoot, createdAt, updatedAt)
                    VALUES ('user1', 'reader@example.com', 'Reader', 0, 1000, 2000)
                    """.trimIndent(),
                )
                v3.close()

                val v4 = helper.runMigrationsAndValidate(version = 4, migrations = listOf(MIGRATION_3_4))

                v4.withStatement(
                    "SELECT displayName, isRoot, canEdit, canShare FROM users WHERE id = 'user1'",
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Reader"
                    statement.getLong(1) shouldBe 0L
                    statement.getLong(2) shouldBe 1L
                    statement.getLong(3) shouldBe 1L
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
