package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST root for the series domain — parent for the nested [Detail], [Books],
 * and [Merge] routes.
 */
@Resource("/api/v1/series")
class SeriesResources {
    /**
     * Series cover bytes — `PUT /api/v1/series/{id}/cover`.
     *
     * Blobs travel over plain HTTP, never the RPC channel; the upload streams as multipart.
     */
    @Resource("{id}/cover")
    class Cover(
        val parent: SeriesResources = SeriesResources(),
        val id: String,
    )
}
