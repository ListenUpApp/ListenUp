package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.server.routes.resources.MergeSeriesBody
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.SeriesResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.io.files.Path

/**
 * REST surface for [SeriesService]. Five endpoints:
 *
 *  - `GET /api/v1/series/{id}` — returns the full [SeriesSyncPayload] for the
 *    given id, or null when no series with that id exists. HTTP 200 on both.
 *    Responds the unwrapped value (third-party RESTful convention).
 *  - `GET /api/v1/series/{id}/books` — returns the [BookSyncPayload]s belonging
 *    to the series in series-position order that the caller can reach. HTTP 200
 *    with an empty list when the series has no accessible books. The scoped service
 *    access-filters the listing (via `BookAccessPolicy`), so an inaccessible book
 *    is simply absent — existence-preserving, identical to a series that has no
 *    accessible books. ROOT/ADMIN bypass the filter.
 *  - `PATCH /api/v1/series/{id}` — applies a [SeriesUpdate] patch to the series.
 *    HTTP 204 on success.
 *  - `DELETE /api/v1/series/{id}` — hard-deletes the series and removes all
 *    junction rows. HTTP 204 on success.
 *  - `POST /api/v1/series/merge` — merges source into target series. HTTP 204
 *    on success.
 *
 * All endpoints require JWT authentication (mounted inside the authenticate block
 * in Application.kt).
 */
private const val AUTH_WALL_REGRESSION_MSG =
    "series REST mount reached without a principal — auth wall regression"

fun Route.seriesRoutes(
    seriesService: SeriesService,
    imageHome: Path,
    imageStorage: ImageStorage,
) {
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
        // The scoped service access-filters the listing: a book the caller can't reach is
        // simply absent — existence-preserving, identical to a series with no accessible
        // books. ROOT/ADMIN bypass the filter inside the service.
        when (val result = call.scoped(seriesService).listBooksBySeries(SeriesId(res.id))) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    patch<SeriesResources.Detail> { res ->
        val patch = call.receive<SeriesUpdate>()
        when (val result = call.scoped(seriesService).updateSeries(SeriesId(res.id), patch)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    put<SeriesResources.Cover> { res ->
        // Store the bytes content-addressed, then persist the path through the scoped service so its
        // internal requireCanEdit gate + revision bump + sync-event publication fire (series' canEdit
        // check is not exposed for a pre-buffer gate; the 10 MiB cap bounds the exposure).
        when (val outcome = call.storeMultipartImage("series", imageHome, imageStorage)) {
            is ImageUploadOutcome.Rejected -> {
                call.respond(outcome.status, outcome.message)
            }

            is ImageUploadOutcome.Stored -> {
                when (
                    val result =
                        call
                            .scoped(seriesService)
                            .updateSeries(SeriesId(res.id), SeriesUpdate(coverPath = outcome.relPath))
                ) {
                    is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
                    is AppResult.Failure -> call.respondBareAppError(result.error)
                }
            }
        }
    }

    delete<SeriesResources.Detail> { res ->
        when (val result = call.scoped(seriesService).deleteSeries(SeriesId(res.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<SeriesResources.Merge> {
        val body = call.receive<MergeSeriesBody>()
        when (val result = call.scoped(seriesService).mergeSeries(body.source, body.target)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

/**
 * Scopes [service] to the authenticated caller so mutation handlers gate on the caller's
 * `canEdit` flag. Reaching this without a principal is an auth-wall regression.
 */
private fun ApplicationCall.scoped(service: SeriesService): SeriesService {
    val p = userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
    return (service as SeriesServiceImpl).copyWith(PrincipalProvider { p })
}

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
