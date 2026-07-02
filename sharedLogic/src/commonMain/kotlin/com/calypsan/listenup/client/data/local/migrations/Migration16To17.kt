package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v16 → v17 — Books-B1 syncable-domain landing.
 *
 * `contributors` and `series` become first-class syncable domains. Each table
 * gains `revision` (monotonic server revision) and `deletedAt` (epoch-ms
 * tombstone). Existing rows are rebuilt with `revision = 0` and `deletedAt`
 * null — the client's `contributors` / `series` sync domain
 * overwrite them with real values on the first catch-up from the server.
 *
 * Tables are rebuilt (create-new → copy → drop → rename) rather than
 * `ALTER TABLE ADD COLUMN`-ed: a NOT-NULL `revision` column needs a constant
 * default to add in place, and the rebuilt schema must match Room's exported
 * v17 schema, which carries no column default.
 */
internal val MIGRATION_16_17: Migration =
    object : Migration(16, 17) {
        override fun migrate(connection: SQLiteConnection) {
            connection.migrateContributors()
            connection.migrateSeries()
        }
    }

private fun SQLiteConnection.migrateContributors() {
    execSQL(
        """
        CREATE TABLE `contributors_new` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `sortName` TEXT,
            `asin` TEXT,
            `description` TEXT,
            `imagePath` TEXT,
            `imageBlurHash` TEXT,
            `website` TEXT,
            `birthDate` TEXT,
            `deathDate` TEXT,
            `revision` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO `contributors_new` (
            `id`, `name`, `sortName`, `asin`, `description`, `imagePath`,
            `imageBlurHash`, `website`, `birthDate`, `deathDate`,
            `revision`, `deletedAt`, `createdAt`, `updatedAt`
        )
        SELECT
            `id`, `name`, `sortName`, `asin`, `description`, `imagePath`,
            `imageBlurHash`, `website`, `birthDate`, `deathDate`,
            0, NULL, `createdAt`, `updatedAt`
        FROM `contributors`
        """.trimIndent(),
    )
    execSQL("DROP TABLE `contributors`")
    execSQL("ALTER TABLE `contributors_new` RENAME TO `contributors`")
}

private fun SQLiteConnection.migrateSeries() {
    execSQL(
        """
        CREATE TABLE `series_new` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `description` TEXT,
            `asin` TEXT,
            `coverPath` TEXT,
            `coverBlurHash` TEXT,
            `revision` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO `series_new` (
            `id`, `name`, `description`, `asin`, `coverPath`, `coverBlurHash`,
            `revision`, `deletedAt`, `createdAt`, `updatedAt`
        )
        SELECT
            `id`, `name`, `description`, `asin`, `coverPath`, `coverBlurHash`,
            0, NULL, `createdAt`, `updatedAt`
        FROM `series`
        """.trimIndent(),
    )
    execSQL("DROP TABLE `series`")
    execSQL("ALTER TABLE `series_new` RENAME TO `series`")
}
