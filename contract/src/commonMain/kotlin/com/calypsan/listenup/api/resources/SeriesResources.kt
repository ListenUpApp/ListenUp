package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST root for the series domain — parent for the nested [Detail] and
 * [Books] routes.
 */
@Resource("/api/v1/series")
class SeriesResources {
    /**
     * REST mirror of [com.calypsan.listenup.api.SeriesService.getSeries] —
     * `GET /api/v1/series/{id}` returns the series aggregate as a
     * [com.calypsan.listenup.api.sync.SeriesSyncPayload?]. Responds 200 on
     * success, 404 when no series with the given id exists. Requires JWT
     * authentication.
     */
    @Resource("{id}")
    class Detail(
        val parent: SeriesResources = SeriesResources(),
        val id: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.SeriesService.listBooksBySeries] —
     * `GET /api/v1/series/{id}/books` returns all books belonging to the series
     * as [List]<[com.calypsan.listenup.api.sync.BookSyncPayload]> in series-position
     * order. Responds 200 with an empty list when the series exists but has no
     * books. Requires JWT authentication.
     */
    @Resource("{id}/books")
    class Books(
        val parent: SeriesResources = SeriesResources(),
        val id: String,
    )
}
