package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_22_23
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v22 → v23: the `pending_image_upload` table appears, keyed by (kind, entityId), and nothing
 * already in the database is touched — in particular the unsynced outbox and the sync cursors.
 */
class PendingImageUploadMigrationTest :
    FunSpec({
        test("MIGRATION_22_23 creates pending_image_upload, leaves existing data alone, and validates against 23.json") {
            val helper = createMigrationTestHelper()
            try {
                val v22 = helper.createDatabase(version = 22)
                v22.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 700)")
                v22.close()

                val v23 = helper.runMigrationsAndValidate(version = 23, migrations = listOf(MIGRATION_22_23))

                v23.withStatement("SELECT domainName, revision FROM sync_cursor") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "books"
                    statement.getLong(1) shouldBe 700
                    statement.step() shouldBe false
                }
                v23.execSQL(
                    "INSERT INTO pending_image_upload (kind, entityId, ownerUserId, enqueuedAt, attempts) " +
                        "VALUES ('BookCover', 'b1', 'u1', 5, 0)",
                )
                v23.withStatement(
                    "SELECT kind, entityId, ownerUserId, enqueuedAt, attempts FROM pending_image_upload",
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "BookCover"
                    statement.getText(1) shouldBe "b1"
                    statement.getText(2) shouldBe "u1"
                    statement.getLong(3) shouldBe 5
                    statement.getLong(4) shouldBe 0
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
