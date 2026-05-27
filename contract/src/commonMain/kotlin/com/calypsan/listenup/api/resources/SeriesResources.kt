package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST root for the series domain — parent for the nested [Detail], [Books],
 * and [Merge] routes.
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

    /**
     * REST mirror of [com.calypsan.listenup.api.SeriesService.mergeSeries] —
     * `POST /api/v1/series/merge` merges the `source` series into the `target`
     * series (body: `{ "source": "<id>", "target": "<id>" }`). Responds 204 on
     * success, 404 when either series is missing, 400 when `source == target`.
     * Requires JWT authentication.
     */
    @Resource("merge")
    class Merge(
        val parent: SeriesResources = SeriesResources(),
    )
}
