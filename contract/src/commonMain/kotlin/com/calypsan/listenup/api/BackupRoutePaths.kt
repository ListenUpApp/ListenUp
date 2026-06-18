package com.calypsan.listenup.api

/**
 * Canonical REST route paths for the admin backup endpoints, shared by server + client to prevent drift.
 *
 * The binary `.listenup.zip` upload is the one backup operation that cannot ride RPC
 * (multipart streaming), so its path is hand-rolled on both the server route and the client
 * uploader. Defining it once here makes client/server drift a compile-time concern, not a
 * runtime 404. Everything else in the backup surface is RPC ([BackupService]).
 */
object BackupRoutePaths {
    /** `POST` — streams a `.listenup.zip` backup; responds a [com.calypsan.listenup.api.dto.backup.BackupSummary]. */
    const val UPLOAD: String = "/api/v1/admin/backups/upload"
}
