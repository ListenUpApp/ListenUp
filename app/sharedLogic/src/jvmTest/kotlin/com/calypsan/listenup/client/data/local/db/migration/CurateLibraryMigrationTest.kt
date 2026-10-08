package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_18_19
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * v18 → v19 splits "Can edit": `users` and `admin_user_roster` gain `canCurateLibrary`, copied from
 * `canEdit` — the same backfill the server's V92 makes, so a mirrored row agrees with the server's
 * without waiting for a re-pull. No cursor is rewound and no revision moves.
 */
class CurateLibraryMigrationTest :
    FunSpec({
        test("MIGRATION_18_19 copies canEdit into canCurateLibrary on users and the roster, and validates against 19.json") {
            val helper = createMigrationTestHelper()
            try {
                val v18 = helper.createDatabase(version = 18)
                v18.execSQL(
                    "INSERT INTO users (id, email, displayName, isRoot, createdAt, updatedAt, canEdit) " +
                        "VALUES ('u1', 'reader@example.com', 'Reader', 0, 1000, 2000, 1)",
                )
                v18.execSQL(
                    "INSERT INTO admin_user_roster (id, email, displayName, role, status, canEdit, accountCreatedAt, revision) " +
                        "VALUES ('m1', 'm@example.com', 'M', 'MEMBER', 'ACTIVE', 1, 1, 5), " +
                        "('m2', 'n@example.com', 'N', 'MEMBER', 'ACTIVE', 0, 1, 6)",
                )
                v18.close()

                val v19 = helper.runMigrationsAndValidate(version = 19, migrations = listOf(MIGRATION_18_19))

                v19.withStatement("SELECT canEdit, canCurateLibrary FROM users WHERE id = 'u1'") { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 1L
                    statement.getLong(1) shouldBe 1L
                }
                v19.withStatement("SELECT id, canCurateLibrary, revision FROM admin_user_roster ORDER BY id") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "m1"
                    statement.getLong(1) shouldBe 1L
                    statement.getLong(2) shouldBe 5L
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "m2"
                    statement.getLong(1) shouldBe 0L
                    statement.getLong(2) shouldBe 6L
                }
            } finally {
                helper.close()
            }
        }
    })
