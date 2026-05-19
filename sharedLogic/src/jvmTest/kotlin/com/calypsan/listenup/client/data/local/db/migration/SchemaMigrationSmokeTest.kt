package com.calypsan.listenup.client.data.local.db.migration

import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test for the Room [androidx.room.testing.MigrationTestHelper] harness.
 *
 * Proves the test plumbing works end-to-end for `ListenUpDatabase` — schema
 * export on disk, driver wiring, constructor factory — so that when the real
 * v1 → v2 migration lands in W4.4 we can assert schema equivalence without
 * scaffolding a helper from scratch. When v2 ships, replace this smoke test
 * with an actual migration-and-validate assertion.
 */
class SchemaMigrationSmokeTest {
    private val helper = createMigrationTestHelper()

    @AfterTest
    fun tearDown() {
        helper.close()
    }

    @Test
    fun `creates current schema database and opens a live connection`() {
        // Pin to the latest exported schema — when W4.4 lands further schema
        // changes, bump this and start asserting actual v(N-1) → v(N) migration
        // behaviour. For now this just proves the harness can load the schema
        // bundle and drive [androidx.sqlite.SQLiteConnection].
        val connection = helper.createDatabase(version = 10)
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { stmt ->
            val tables =
                buildList {
                    while (stmt.step()) add(stmt.getText(0))
                }
            assertTrue(
                "books" in tables,
                "schema must define the `books` table — saw $tables",
            )
        }
    }
}
