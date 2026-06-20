@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.api

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Canonical REST route paths for the admin backup endpoints, shared by server + client to prevent drift.
 *
 * The binary `.listenup.zip` upload is the one backup operation that cannot ride RPC
 * (multipart streaming), so its path is hand-rolled on both the server route and the client
 * uploader. Defining it once here makes client/server drift a compile-time concern, not a
 * runtime 404. Everything else in the backup surface is RPC ([BackupService]).
 */
@HiddenFromObjC
object BackupRoutePaths {
    /** `POST` — streams a `.listenup.zip` backup; responds a [com.calypsan.listenup.api.dto.backup.BackupSummary]. */
    const val UPLOAD: String = "/api/v1/admin/backups/upload"

    /**
     * `GET` route template (Ktor path with the `{id}` placeholder) — streams the `.listenup.zip`
     * archive identified by `id` as a file attachment. Used to register the server route.
     */
    const val DOWNLOAD_TEMPLATE: String = "/api/v1/admin/backups/{id}/download"

    /**
     * Concrete `GET` download path for the backup identified by [id] — used by the client downloader.
     * Server backup ids are filesystem-safe (`backup-<iso>`), so no URL-encoding is required.
     */
    fun downloadFor(id: String): String = "/api/v1/admin/backups/$id/download"
}
