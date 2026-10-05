package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_16_17
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v16 → v17: `libraries` gains `metadataRegion`, the library's Audible store, so matching can
 * start a search there. Rows mirrored before it have no store, so the migration rewinds the `libraries`
 * cursor and the next catch-up re-pulls the (one) library with it. Every other cursor is left alone.
 */
class LibraryMetadataRegionMigrationTest :
    FunSpec({
        test("MIGRATION_16_17 adds metadataRegion, keeps the library, rewinds only its cursor, and validates against 17.json") {
            val helper = createMigrationTestHelper()
            try {
                val v16 = helper.createDatabase(version = 16)
                v16.execSQL(
                    "INSERT INTO libraries (id, name, metadataPrecedence, accessMode, createdByUserId, createdAt, revision, deletedAt, " +
                        "initialScanCompletedAt) VALUES ('lib', 'Library', 'embedded,abs,sidecar', 'shared', NULL, 0, 5, NULL, 1)",
                )
                v16.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('libraries', 500)")
                v16.execSQL("INSERT INTO sync_cursor (domainName, revision) VALUES ('books', 700)")
                v16.close()

                val v17 = helper.runMigrationsAndValidate(version = 17, migrations = listOf(MIGRATION_16_17))

                v17.withStatement("SELECT id, metadataRegion FROM libraries") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "lib"
                    statement.isNull(1) shouldBe true
                    statement.step() shouldBe false
                }
                v17.withStatement("SELECT domainName FROM sync_cursor ORDER BY domainName") { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "books"
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
