package com.calypsan.listenup.client.data.local.db.migration

import com.calypsan.listenup.client.data.local.migrations.MIGRATION_1_2
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression proof for the real v1 → v2 migration (SERVER-SYNC-04): `collection_books`/
 * `book_tags`/`book_moods` each gain a `syncId` column, and any pre-migration row in those three
 * tables is deleted (its real opaque wire id is unknowable — see [MIGRATION_1_2]'s KDoc).
 *
 * Seeds one row per affected junction table (plus an unrelated `collections` row, to prove the
 * migration touches only the three junction tables) against the v1 schema, runs the real
 * [MIGRATION_1_2], and validates the result against the exported v2 schema.
 */
class SchemaMigrationSmokeTest :
    FunSpec({
        test("v1 to v2 migration adds syncId to junction tables and deletes their pre-migration rows") {
            val helper = createMigrationTestHelper()
            try {
                val v1 = helper.createDatabase(version = 1)
                val now = 1_700_000_000_000L
                v1
                    .prepare(
                        "INSERT INTO libraries (id, name, metadataPrecedence, accessMode, createdAt, revision) " +
                            "VALUES ('lib1', 'Lib', 'embedded,abs,sidecar', 'shared', $now, 0)",
                    ).use { it.step() }
                v1
                    .prepare(
                        "INSERT INTO collections (id, libraryId, ownerId, name, isInbox, revision, updatedAt) " +
                            "VALUES ('col1', 'lib1', 'user1', 'Collection', 0, 0, $now)",
                    ).use { it.step() }
                v1
                    .prepare(
                        "INSERT INTO collection_books (collectionId, bookId, createdAt, revision, deletedAt) " +
                            "VALUES ('col1', 'book1', $now, 0, NULL)",
                    ).use { it.step() }
                v1
                    .prepare(
                        "INSERT INTO book_tags (bookId, tagId, createdAt, revision, deletedAt) " +
                            "VALUES ('book1', 'tag1', $now, 0, NULL)",
                    ).use { it.step() }
                v1
                    .prepare(
                        "INSERT INTO book_moods (bookId, moodId, createdAt, revision, deletedAt) " +
                            "VALUES ('book1', 'mood1', $now, 0, NULL)",
                    ).use { it.step() }
                v1.close()

                val v2 = helper.runMigrationsAndValidate(version = 2, migrations = listOf(MIGRATION_1_2))

                // The unrelated collections row survives the migration untouched.
                v2.prepare("SELECT COUNT(*) FROM collections WHERE id = 'col1'").use { stmt ->
                    stmt.step()
                    withClue("the collections row must survive the junction-only migration") {
                        stmt.getLong(0) shouldBe 1L
                    }
                }

                // Every pre-migration junction row is gone — the healing path is a re-pull, not a carry-over.
                for (table in listOf("collection_books", "book_tags", "book_moods")) {
                    v2.prepare("SELECT COUNT(*) FROM $table").use { stmt ->
                        stmt.step()
                        withClue("$table must be emptied by the v1->v2 migration (unknowable pre-migration syncId)") {
                            stmt.getLong(0) shouldBe 0L
                        }
                    }
                }

                // The syncId column + unique index now exist and accept a fresh opaque row.
                v2
                    .prepare(
                        "INSERT INTO collection_books (collectionId, bookId, syncId, createdAt, revision, deletedAt) " +
                            "VALUES ('col1', 'book1', 'opaque-id-1', $now, 0, NULL)",
                    ).use { it.step() }
                v2.prepare("SELECT syncId FROM collection_books WHERE collectionId = 'col1'").use { stmt ->
                    stmt.step()
                    stmt.getText(0) shouldBe "opaque-id-1"
                }
                v2.close()
            } finally {
                helper.close()
            }
        }
    })
