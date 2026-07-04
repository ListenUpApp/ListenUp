package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

/**
 * Regression test for [MIGRATION_46_47] — promotes `activities` from a denormalized-display cache
 * to a cursored sync mirror.
 *
 * The old table carried denormalized display columns (userDisplayName, bookCoverPath, …), filled by
 * the feed RPC. The new shape carries only the RAW activity fields plus the sync substrate
 * (`revision`, `deletedAt`); identity and book display are enriched at READ time by joining the
 * local `public_profiles` and book mirrors. Because `activities` is now a MirroredDomain whose
 * cursor starts at 0, the rows re-populate from the server via catch-up — so dropping the old cache
 * loses no user data.
 *
 * `runMigrationsAndValidate` fails if the migrated shape diverges from the generated v47 schema, so
 * this also proves the recreated CREATE TABLE / indices match the entity definition byte-for-byte.
 */
class Migration46To47Test :
    FunSpec({

        test("v46 → v47 rebuilds activities with the sync substrate and drops the denormalized columns") {
            val helper = createMigrationTestHelper()
            try {
                val v46 = helper.createDatabase(version = 46)
                // Seed a row in the OLD denormalized shape so the migration exercises DROP on a
                // populated table (the rows are server-owned and re-sync via catch-up).
                v46.execSQL(
                    "INSERT INTO `activities` (" +
                        "`id`, `userId`, `type`, `occurredAt`, " +
                        "`userDisplayName`, `userAvatarColor`, `userAvatarType`, `userAvatarValue`, " +
                        "`bookId`, `bookTitle`, `bookAuthorName`, `bookCoverPath`, " +
                        "`isReread`, `durationMs`, `milestoneValue`, `milestoneUnit`, `shelfId`, `shelfName`" +
                        ") VALUES (" +
                        "'a1', 'u1', 'finished_book', 100, " +
                        "'John Smith', '#FF5733', 'auto', NULL, " +
                        "'b1', 'The Way of Kings', 'Brandon Sanderson', NULL, " +
                        "0, 3600000, 0, NULL, NULL, NULL)",
                )

                val db = helper.runMigrationsAndValidate(version = 47, migrations = listOf(MIGRATION_46_47))

                val columns = db.columnsOf("activities")
                // The sync substrate is present.
                columns shouldContain "revision"
                columns shouldContain "deletedAt"
                // The raw activity fields survive.
                columns shouldContain "userId"
                columns shouldContain "occurredAt"
                columns shouldContain "bookId"
                // The denormalized display columns are gone.
                columns shouldNotContain "userDisplayName"
                columns shouldNotContain "userAvatarColor"
                columns shouldNotContain "userAvatarType"
                columns shouldNotContain "userAvatarValue"
                columns shouldNotContain "bookTitle"
                columns shouldNotContain "bookAuthorName"
                columns shouldNotContain "bookCoverPath"
            } finally {
                helper.close()
            }
        }
    })

/** Column names of [table], read from `PRAGMA table_info`. */
private fun SQLiteConnection.columnsOf(table: String): Set<String> =
    prepare("PRAGMA table_info(`$table`)").use { stmt ->
        buildSet {
            while (stmt.step()) add(stmt.getText(1))
        }
    }
