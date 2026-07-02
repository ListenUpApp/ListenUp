package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * Regression test for [MIGRATION_43_44] — the two offline mirror tables for readers/presence.
 *
 * Asserts the migration creates `book_readership` and `cached_active_sessions` with the expected
 * columns and that a round-trip insert into each succeeds. `runMigrationsAndValidate` additionally
 * fails if the migrated shape diverges from the generated v44 schema.
 */
class Migration43To44Test :
    FunSpec({

        test("v43 → v44 creates the book_readership and cached_active_sessions mirror tables") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 43)

                val db = helper.runMigrationsAndValidate(version = 44, migrations = listOf(MIGRATION_43_44))

                db.columnsOf("book_readership") shouldContainAll
                    setOf("bookId", "userId", "displayName", "avatarType", "currentProgressPct", "finishesJson", "observedAt")
                db.columnsOf("cached_active_sessions") shouldContainAll
                    setOf("userId", "displayName", "avatarType", "bookId", "startedAtMs", "observedAt")

                // Round-trip a readership row (including a null progress and a comma-joined finishes list).
                db.execSQL(
                    "INSERT INTO book_readership " +
                        "(bookId, userId, displayName, avatarType, currentProgressPct, finishesJson, observedAt) " +
                        "VALUES ('b1', 'u1', 'Alice', 'auto', NULL, '300,100', 5000)",
                )
                db
                    .prepare("SELECT displayName, finishesJson FROM book_readership WHERE bookId = 'b1' AND userId = 'u1'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "Alice"
                        stmt.getText(1) shouldBe "300,100"
                    }

                // Round-trip a presence row.
                db.execSQL(
                    "INSERT INTO cached_active_sessions " +
                        "(userId, displayName, avatarType, bookId, startedAtMs, observedAt) " +
                        "VALUES ('u2', 'Bob', 'auto', 'b2', 1000, 5000)",
                )
                db
                    .prepare("SELECT displayName, bookId FROM cached_active_sessions WHERE userId = 'u2'")
                    .use { stmt ->
                        stmt.step().shouldBeTrue()
                        stmt.getText(0) shouldBe "Bob"
                        stmt.getText(1) shouldBe "b2"
                    }
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
