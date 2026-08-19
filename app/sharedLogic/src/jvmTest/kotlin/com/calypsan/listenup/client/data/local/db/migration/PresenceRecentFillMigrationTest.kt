package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_4_5
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v4 → v5: the presence cache learns to hold non-live rows.
 *
 * `cached_active_sessions` used to mirror live sessions only, so its timestamp column could be
 * called `startedAtMs` honestly. The section now also carries each other person's most recently
 * played book, whose timestamp is a `lastPlayedAt` — one meaning per row, not two — so the column
 * is renamed to `lastActiveAtMs` and joined by an `isLive` discriminator.
 *
 * Both statements are non-destructive `ALTER TABLE`s (rename + add), per the migration policy in
 * `ListenUpDatabase`: this table is only a cache, but the database it lives in holds the unsynced
 * outbox, and the builder registers one migration list for every table. A destructive fallback here
 * would take the outbox with it.
 *
 * `isLive DEFAULT 1` is the substance, not boilerplate. Every row already in this cache was written
 * from a live session — that is all the section could hold before — so `1` preserves each existing
 * row's meaning exactly. Defaulting to `0` would relabel everyone as "last seen a while ago" on
 * upgrade, and the roster is replaced wholesale on the next presence ping anyway, so the wrong
 * default would show a brief, confident lie rather than a brief absence.
 */
class PresenceRecentFillMigrationTest :
    FunSpec({
        test("MIGRATION_4_5 preserves cached presence rows, keeps the timestamp, and marks them live") {
            val helper = createMigrationTestHelper()
            try {
                val v4 = helper.createDatabase(version = 4)
                v4.execSQL(
                    """
                    INSERT INTO cached_active_sessions
                        (userId, displayName, avatarType, bookId, startedAtMs, observedAt)
                    VALUES ('user1', 'Reader', 'auto', 'book1', 1700, 1800)
                    """.trimIndent(),
                )
                v4.close()

                val v5 = helper.runMigrationsAndValidate(version = 5, migrations = listOf(MIGRATION_4_5))

                v5.withStatement(
                    """
                    SELECT displayName, avatarType, bookId, lastActiveAtMs, isLive, observedAt
                    FROM cached_active_sessions WHERE userId = 'user1'
                    """.trimIndent(),
                ) { statement ->
                    statement.step() shouldBe true
                    statement.getText(0) shouldBe "Reader"
                    statement.getText(1) shouldBe "auto"
                    statement.getText(2) shouldBe "book1"
                    // The rename carries the value across; it is not re-derived or zeroed.
                    statement.getLong(3) shouldBe 1700L
                    // Everything cached before v5 was a live session.
                    statement.getLong(4) shouldBe 1L
                    statement.getLong(5) shouldBe 1800L
                    statement.step() shouldBe false
                }
            } finally {
                helper.close()
            }
        }
    })
