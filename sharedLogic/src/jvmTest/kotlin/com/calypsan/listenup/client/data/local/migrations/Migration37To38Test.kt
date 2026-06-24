package com.calypsan.listenup.client.data.local.migrations

import androidx.sqlite.SQLiteConnection
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

/**
 * Regression test for [MIGRATION_37_38] — adds the five audio-stream columns to `audio_files`.
 *
 * `runMigrationsAndValidate(version = 38, …)` runs the migration and validates the
 * post-migration schema against the exported `38.json`, asserting the new columns are
 * present after the upgrade.
 */
class Migration37To38Test :
    FunSpec({

        test("v37 → v38 adds the audio-stream columns to audio_files") {
            val helper = createMigrationTestHelper()
            try {
                helper.createDatabase(version = 37)

                val db =
                    helper.runMigrationsAndValidate(
                        version = 38,
                        migrations = listOf(MIGRATION_37_38),
                    )

                db.columnsOf("audio_files") shouldContainAll
                    setOf("codecProfile", "spatial", "bitrate", "sampleRate", "channels")
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
