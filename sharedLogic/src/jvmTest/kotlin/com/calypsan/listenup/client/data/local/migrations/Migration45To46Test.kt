package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Regression test for [MIGRATION_45_46] — adds `initialScanCompletedAt` to `libraries` and backfills
 * it from `createdAt` for any library that already holds a live book.
 *
 * `runMigrationsAndValidate` fails if the migrated shape diverges from the generated v46 schema, so
 * this also proves the ADD COLUMN result matches the entity definition. The backfill assertions prove
 * a returning device (populated library) never re-shows the "Building your library" screen even before
 * the server's stamped flag syncs down.
 */
class Migration45To46Test :
    FunSpec({

        test("v45 → v46 adds initialScanCompletedAt to libraries and backfills populated libraries only") {
            val helper = createMigrationTestHelper()
            try {
                val v45 = helper.createDatabase(version = 45)
                // populated: has a live book → backfilled from createdAt.
                // empty: no books → stays null.
                // deleted-books: only a tombstoned book → stays null.
                v45.execSQL(
                    "INSERT INTO `libraries` (`id`, `name`, `metadataPrecedence`, `accessMode`, `createdAt`, `revision`) VALUES " +
                        "('populated', 'Populated', 'embedded', 'shared', 4242, 1), " +
                        "('empty', 'Empty', 'embedded', 'shared', 4242, 1), " +
                        "('deleted-books', 'DeletedBooks', 'embedded', 'shared', 4242, 1)",
                )
                v45.execSQL(
                    "INSERT INTO `books` (`id`, `libraryId`, `folderId`, `title`, `totalDuration`, `abridged`, " +
                        "`revision`, `hasScanWarning`, `createdAt`, `updatedAt`, `deletedAt`) VALUES " +
                        "('b1', 'populated', 'f', 'Book', 0, 0, 0, 0, 0, 0, NULL), " +
                        "('b2', 'deleted-books', 'f', 'Gone', 0, 0, 0, 0, 0, 0, 999)",
                )

                val db = helper.runMigrationsAndValidate(version = 46, migrations = listOf(MIGRATION_45_46))

                db.columnsOf("libraries") shouldContain "initialScanCompletedAt"
                db.stampOf("populated") shouldBe 4242L // backfilled from createdAt
                db.stampOf("empty") shouldBe null // never scanned
                db.stampOf("deleted-books") shouldBe null // only a tombstoned book → not counted
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

/** The `initialScanCompletedAt` for library [id], or null. */
private fun SQLiteConnection.stampOf(id: String): Long? =
    prepare("SELECT initialScanCompletedAt FROM libraries WHERE id = ?").use { stmt ->
        stmt.bindText(1, id)
        if (stmt.step() && !stmt.isNull(0)) stmt.getLong(0) else null
    }
