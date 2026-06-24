package com.calypsan.listenup.client.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room schema migration v37 → v38 — add the five audio-stream columns to `audio_files`.
 *
 * All five are nullable with no default: existing rows stay NULL until a library re-scan
 * repopulates them from the server wire payload
 * ([com.calypsan.listenup.api.sync.BookAudioFilePayload]). Text columns hold `codecProfile`
 * (AAC profile token) and `spatial` (e.g. `atmos`); integer columns hold `bitrate` (bits/sec),
 * `sampleRate` (Hz), and `channels`.
 */
internal val MIGRATION_37_38: Migration =
    object : Migration(37, 38) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `audio_files` ADD COLUMN `codecProfile` TEXT")
            connection.execSQL("ALTER TABLE `audio_files` ADD COLUMN `spatial` TEXT")
            connection.execSQL("ALTER TABLE `audio_files` ADD COLUMN `bitrate` INTEGER")
            connection.execSQL("ALTER TABLE `audio_files` ADD COLUMN `sampleRate` INTEGER")
            connection.execSQL("ALTER TABLE `audio_files` ADD COLUMN `channels` INTEGER")
        }
    }
