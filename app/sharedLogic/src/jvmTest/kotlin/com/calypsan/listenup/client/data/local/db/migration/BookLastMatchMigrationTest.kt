package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_17_18
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v17 → v18: `books` gains `lastMatch`, the book's live metadata match. Existing books have none
 * (null), and no sync cursor is rewound — re-pulling the whole library for a field nothing has yet would cost
 * far more than it could recover.
 */
class BookLastMatchMigrationTest :
    FunSpec({
        test("MIGRATION_17_18 adds a null lastMatch, keeps every book and cursor, and validates against 18.json") {
            val helper = createMigrationTestHelper()
            try {
                val v17 = helper.createDatabase(version = 17)
                v17.execSQL(
                    "INSERT INTO books (id, libraryId, folderId, title, totalDuration, abridged, revision, hasScanWarning, createdAt, updatedAt) " +
                        "VALUES ('b1', 'lib', 'f', 'Book', 0, 0, 3, 0, 0, 0)",
                )
                v17.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 700)")
                v17.close()

                val v18 = helper.runMigrationsAndValidate(version = 18, migrations = listOf(MIGRATION_17_18))

                v18.withStatement("SELECT id, lastMatch FROM books") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "b1"
                    statement.isNull(1) shouldBe true
                }
                v18.withStatement("SELECT revision FROM sync_cursor WHERE domainName = 'books'") { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe 700
                }
            } finally {
                helper.close()
            }
        }
    })
