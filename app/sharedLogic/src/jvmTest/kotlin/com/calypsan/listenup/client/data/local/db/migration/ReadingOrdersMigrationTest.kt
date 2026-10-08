package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_20_21
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v20 → v21 (#962): three reading-order tables, the `canMakeReadingOrders` flag on `users` and
 * `admin_user_roster` (DEFAULT 1 — additive, undoable work defaults on, matching the server's V96), and
 * `books.releaseDate` (null; no cursor rewind — Publication order is year-exact at once and day-exact as
 * books next sync).
 */
class ReadingOrdersMigrationTest :
    FunSpec({
        test("MIGRATION_20_21 adds the tables and columns, defaults the permission on, keeps cursors, validates 21.json") {
            val helper = createMigrationTestHelper()
            try {
                val v20 = helper.createDatabase(version = 20)
                v20.execSQL(
                    "INSERT INTO books (id, libraryId, folderId, title, totalDuration, abridged, revision, hasScanWarning, " +
                        "createdAt, updatedAt) VALUES ('b1', 'lib', 'f', 'Book', 0, 0, 3, 0, 0, 0)",
                )
                v20.execSQL(
                    "INSERT INTO users (id, email, displayName, isRoot, createdAt, updatedAt, canEdit, canCurateLibrary, " +
                        "canContributeStoryWorld, canCurateStoryWorld) VALUES ('u1', 'a@b.c', 'A', 0, 0, 0, 1, 0, 1, 0)",
                )
                v20.execSQL(
                    "INSERT INTO admin_user_roster (id, email, displayName, role, status, canEdit, canCurateLibrary, " +
                        "canContributeStoryWorld, canCurateStoryWorld, accountCreatedAt, revision) " +
                        "VALUES ('u1', 'a@b.c', 'A', 'MEMBER', 'ACTIVE', 1, 0, 1, 0, 0, 1)",
                )
                v20.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 700)")
                v20.close()

                val v21 = helper.runMigrationsAndValidate(version = 21, migrations = listOf(MIGRATION_20_21))

                v21.withStatement("SELECT releaseDate FROM books WHERE id = 'b1'") { s ->
                    s.step() shouldBe true
                    s.isNull(0) shouldBe true
                }
                v21.withStatement("SELECT revision FROM sync_cursor WHERE domainName = 'books'") { s ->
                    s.step() shouldBe true
                    s.getLong(0) shouldBe 700
                }
                for (table in listOf("users", "admin_user_roster")) {
                    v21.withStatement("SELECT canMakeReadingOrders FROM $table WHERE id = 'u1'") { s ->
                        s.step() shouldBe true
                        s.getLong(0) shouldBe 1
                    }
                    v21.withStatement(
                        "SELECT dflt_value FROM pragma_table_info('$table') WHERE name = 'canMakeReadingOrders'",
                    ) { s ->
                        s.step() shouldBe true
                        s.getText(0) shouldBe "1"
                    }
                }
                for (table in listOf("reading_orders", "reading_order_books", "reading_order_follows")) {
                    v21.withStatement("SELECT COUNT(*) FROM $table") { s ->
                        s.step() shouldBe true
                        s.getLong(0) shouldBe 0
                    }
                }
            } finally {
                helper.close()
            }
        }
    })
