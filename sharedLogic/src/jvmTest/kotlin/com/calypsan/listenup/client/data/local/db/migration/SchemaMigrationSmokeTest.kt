package com.calypsan.listenup.client.data.local.db.migration

import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test for the Room [androidx.room.testing.MigrationTestHelper] harness.
 *
 * Proves the test plumbing works end-to-end for `ListenUpDatabase` — schema
 * export on disk, driver wiring, constructor factory — so that when the real
 * v1 → v2 migration lands, schema equivalence can be asserted without
 * scaffolding a helper from scratch. When v2 ships, replace this smoke test
 * with an actual migration-and-validate assertion.
 */
class SchemaMigrationSmokeTest :
    FunSpec({
        test("creates current schema database and opens a live connection") {
            val helper = createMigrationTestHelper()
            try {
                // Pin to the latest exported schema — when further schema
                // changes land, bump this and start asserting actual v(N-1) → v(N) migration
                // behaviour. Today this just proves the harness can load the schema
                // bundle and drive [androidx.sqlite.SQLiteConnection].
                val connection = helper.createDatabase(version = 4)
                connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { stmt ->
                    val tables =
                        buildList {
                            while (stmt.step()) add(stmt.getText(0))
                        }
                    withClue("schema must define the `books` table — saw $tables") {
                        ("books" in tables) shouldBe true
                    }
                }
            } finally {
                helper.close()
            }
        }
    })
