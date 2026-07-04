package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Promotes `activities` from a denormalized-display cache to a cursored sync mirror.
 *
 * The old table carried denormalized display columns (userDisplayName, bookCoverPath, …) and was
 * filled by the feed RPC. The new shape carries only the RAW activity fields plus the sync substrate
 * (`revision`, `deletedAt`) — identity and book display are enriched at READ time by joining the
 * local `public_profiles` and book mirrors. Because `activities` is now a MirroredDomain whose cursor
 * starts at 0, the rows re-populate from the server via catch-up on the next sync — so **dropping the
 * old cache loses no user data** (unlike a book/position table, the feed is a server-owned read
 * model). A rebuild is therefore both simplest and safe.
 *
 * The recreated shape must match Room's generated v47 schema for `activities` exactly, or
 * `runMigrationsAndValidate` fails.
 */
internal val MIGRATION_46_47: Migration =
    object : Migration(46, 47) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS `activities`")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `activities` (" +
                    "`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`occurredAt` INTEGER NOT NULL, `bookId` TEXT, `isReread` INTEGER NOT NULL, " +
                    "`durationMs` INTEGER NOT NULL, `milestoneValue` INTEGER NOT NULL, " +
                    "`milestoneUnit` TEXT, `shelfId` TEXT, `shelfName` TEXT, " +
                    "`revision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_userId` ON `activities` (`userId`)")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_activities_occurredAt` ON `activities` (`occurredAt`)",
            )
            // Defensive: clear any persisted `activities` sync cursor so catch-up re-populates the
            // just-dropped table from revision 0. No such cursor exists today (activities was a
            // refreshed-tier domain that stored none), but a stale cursor here would strand all
            // below-cursor history — this DELETE is the load-bearing safety a future domain-promotion
            // migration must copy alongside the table rebuild.
            connection.execSQL("DELETE FROM `sync_cursor` WHERE `domainName` = 'activities'")
        }
    }
