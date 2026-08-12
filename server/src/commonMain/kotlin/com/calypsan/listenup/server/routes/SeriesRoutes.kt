package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.SeriesResources
import com.calypsan.listenup.api.result.AppResult
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
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * REST surface for [SeriesService]. One endpoint:
 *
 *  - `PUT /api/v1/series/{id}/cover` — uploads a series cover image,
 *    content-addressed under [imageHome], then applies the resulting path via
 *    a [SeriesUpdate] patch through the scoped service (so `canEdit`, revision
 *    bump, and sync-event publication all fire). HTTP 204 on success; a
 *    rejected update deletes the just-written file so rejected uploads can't
 *    accumulate on disk.
 *
 * Every other [SeriesService] operation (get, list books, patch, delete,
 * merge) is RPC-only — this file used to also mirror a merge REST endpoint,
 * but that handler was never registered; the cover upload above is the only
 * route this file actually mounts.
 *
 * Requires JWT authentication (mounted inside the authenticate block in
 * Application.kt).
 */
private const val AUTH_WALL_REGRESSION_MSG =
    "series REST mount reached without a principal — auth wall regression"

fun Route.seriesRoutes(
    seriesService: SeriesService,
    imageHome: Path,
    imageStorage: ImageStorage,
) {
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
                    is AppResult.Success -> {
                        call.respond(HttpStatusCode.NoContent)
                    }

                    is AppResult.Failure -> {
                        // The scoped update rejected (no canEdit / unknown id) — remove the file this
                        // request just wrote so rejected uploads can't accumulate on disk.
                        SystemFileSystem.delete(Path(imageHome.toString(), outcome.relPath), mustExist = false)
                        call.respondBareAppError(result.error)
                    }
                }
            }
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
