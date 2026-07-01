package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v41 → v42 — add the `admin_user_roster` table.
 *
 * Backs the admin-only `admin_user_roster` sync domain: one row per ACTIVE/PENDING_APPROVAL
 * user, carrying exactly the fields the admin Users/pending-approval lists render. The
 * CREATE TABLE mirrors the exported `42.json` schema exactly: single-column PK `id`, no
 * foreign keys, no indices. A fresh install creates this table directly from the entity;
 * only upgrades from v41 run this migration.
 */
internal val MIGRATION_41_42: Migration =
    object : Migration(41, 42) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `admin_user_roster` " +
                    "(`id` TEXT NOT NULL, `email` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `status` TEXT NOT NULL, `canShare` INTEGER NOT NULL, " +
                    "`accountCreatedAt` INTEGER NOT NULL, `revision` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, PRIMARY KEY(`id`))",
            )
        }
    }
