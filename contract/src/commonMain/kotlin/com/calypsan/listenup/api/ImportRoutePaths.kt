package com.calypsan.listenup.api

/**
 * Canonical REST paths for the admin Audiobookshelf import surface.
 *
 * The binary `.audiobookshelf` upload is the one import operation that cannot ride RPC
 * (multipart streaming), so its path is hand-rolled on both the server route and the client
 * uploader. Defining it once here makes client/server drift a compile-time concern, not a
 * runtime 404. Everything else in the import surface is RPC ([ImportService]).
 */
object ImportRoutePaths {
    /** `POST` — streams a `.audiobookshelf` zip; responds an [com.calypsan.listenup.api.dto.imports.ImportSummary]. */
    const val ABS_UPLOAD: String = "/api/v1/admin/imports/abs/upload"
}
