package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_21_22
import com.calypsan.listenup.client.data.local.db.MIGRATION_22_23
import com.calypsan.listenup.client.data.local.db.MIGRATION_23_24
import com.calypsan.listenup.client.data.local.db.MIGRATION_24_25
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates the whole v21 → v25 chain in one run: the Story World event tables, `book_ratings.source`, the
 * roster-cursor rewind and `pending_image_upload`, applied in order to one database. Each step has its own
 * spec; this one pins that they compose, and that the two cursor rewinds remove their own rows and no other.
 */
class V21ToV25MigrationChainTest :
    FunSpec({
        test("a v21 database migrates to v25, validates against 25.json, and keeps what no step owns") {
            val helper = createMigrationTestHelper()
            try {
                val v21 = helper.createDatabase(version = 21)
                v21.execSQL(
                    "INSERT INTO book_ratings (bookId, userId, syncId, halfStars, note, ratedAt, updatedAt, revision, deletedAt) " +
                        "VALUES ('b1', 'u1', 'r1', 7, NULL, 1, 1, 4, NULL)",
                )
                v21.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('book_ratings', 400)")
                v21.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('admin_user_roster', 500)")
                v21.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 700)")
                v21.close()

                val v25 =
                    helper.runMigrationsAndValidate(
                        version = 25,
                        migrations = listOf(MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25),
                    )

                v25.withStatement("SELECT halfStars, source FROM book_ratings WHERE syncId = 'r1'") { row ->
                    row.step() shouldBe true
                    row.getLong(0) shouldBe 7
                    row.getText(1) shouldBe "LISTENUP"
                }
                v25.withStatement("SELECT domainName, revision FROM sync_cursor ORDER BY domainName") { row ->
                    row.step() shouldBe true
                    row.getText(0) shouldBe "books"
                    row.getLong(1) shouldBe 700
                    row.step() shouldBe false
                }
                listOf("world_events", "world_event_mentions", "pending_image_upload").forEach { table ->
                    v25.withStatement("SELECT COUNT(*) FROM $table") { row ->
                        row.step() shouldBe true
                        row.getLong(0) shouldBe 0
                    }
                }
            } finally {
                helper.close()
            }
        }
    })
