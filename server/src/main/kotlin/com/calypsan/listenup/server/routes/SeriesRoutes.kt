package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.SeriesResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST surface for [SeriesService]. Four endpoints:
 *
 *  - `GET /api/v1/series/{id}` — returns the full [SeriesSyncPayload] for the
 *    given id, or null when no series with that id exists. HTTP 200 on both.
 *    Responds the unwrapped value (third-party RESTful convention).
 *  - `GET /api/v1/series/{id}/books` — returns all [BookSyncPayload]s belonging
 *    to the series in series-position order. HTTP 200 with an empty list when
 *    the series has no books.
 *  - `PATCH /api/v1/series/{id}` — applies a [SeriesUpdate] patch to the series.
 *    HTTP 204 on success.
 *  - `DELETE /api/v1/series/{id}` — hard-deletes the series and removes all
 *    junction rows. HTTP 204 on success.
 *
 * All endpoints require JWT authentication (mounted inside the authenticate block
 * in Application.kt).
 */
fun Route.seriesRoutes(seriesService: SeriesService) {
    get<SeriesResources.Detail> { res ->
        when (val result = seriesService.getSeries(SeriesId(res.id))) {
            is AppResult.Success -> {
                val payload = result.data
                if (payload != null) call.respond(payload) else call.respond(HttpStatusCode.NotFound)
            }

            is AppResult.Failure -> {
                call.respondBareAppError(result.error)
            }
        }
    }

    get<SeriesResources.Books> { res ->
        when (val result = seriesService.listBooksBySeries(SeriesId(res.id))) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    patch<SeriesResources.Detail> { res ->
        val patch = call.receive<SeriesUpdate>()
        when (val result = seriesService.updateSeries(SeriesId(res.id), patch)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    delete<SeriesResources.Detail> { res ->
        when (val result = seriesService.deleteSeries(SeriesId(res.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
