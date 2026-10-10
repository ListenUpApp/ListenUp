package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_21_22
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v21 → v22: no table changes, but the `admin_user_roster` cursor is removed so the next catch-up
 * re-pulls the roster and every permission column holds the server's value rather than the migrations'
 * guess. Every other cursor, and the roster rows themselves, are left alone.
 */
class AdminRosterCursorRewindMigrationTest :
    FunSpec({
        test("MIGRATION_21_22 keeps the roster row, rewinds only the roster cursor, and validates against 22.json") {
            val helper = createMigrationTestHelper()
            try {
                val v21 = helper.createDatabase(version = 21)
                v21.execSQL(
                    "INSERT INTO admin_user_roster (id, email, displayName, role, status, canEdit, canCurateLibrary, " +
                        "canContributeStoryWorld, canCurateStoryWorld, canMakeReadingOrders, accountCreatedAt, revision) " +
                        "VALUES ('u1', 'a@b.c', 'A', 'MEMBER', 'ACTIVE', 1, 0, 1, 0, 1, 0, 7)",
                )
                v21.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('admin_user_roster', 500)")
                v21.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 700)")
                v21.close()

                val v22 = helper.runMigrationsAndValidate(version = 22, migrations = listOf(MIGRATION_21_22))

                v22.withStatement("SELECT id, canCurateStoryWorld, revision FROM admin_user_roster") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "u1"
                    statement.getLong(1) shouldBe 0
                    statement.getLong(2) shouldBe 7
                    statement.step() shouldBe false
                }
                v22.withStatement("SELECT domainName FROM sync_cursor ORDER BY domainName") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "books"
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
