package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.server.routes.resources.MergeContributorsBody
import com.calypsan.listenup.server.routes.resources.UnmergeContributorBody
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.ContributorResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.api.ContributorServiceImpl
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
import kotlinx.io.files.SystemFileSystem

/**
 * REST surface for [ContributorService]. Seven endpoints:
 *
 *  - `GET /api/v1/contributors/{id}` — returns the full [ContributorSyncPayload]
 *    for the given id, or null when no contributor with that id exists. HTTP 200
 *    on both (null is a valid "not cached yet" response — clients show a stub
 *    while sync catches up). Follows the third-party RESTful convention: responds
 *    the unwrapped value, not the [AppResult] envelope.
 *  - `GET /api/v1/contributors/{id}/books` — returns the [BookSyncPayload]s
 *    associated with the contributor that the caller can reach. HTTP 200 with an
 *    empty list when the contributor has no accessible books. The scoped service
 *    access-filters the listing (via `BookAccessPolicy`), so an inaccessible book
 *    is simply absent — existence-preserving, identical to a contributor that has
 *    no accessible books. ROOT/ADMIN bypass the filter.
 *  - `PATCH /api/v1/contributors/{id}` — applies a [ContributorUpdate] patch to
 *    the contributor. HTTP 204 on success.
 *  - `DELETE /api/v1/contributors/{id}` — hard-deletes the contributor and
 *    removes all junction rows. HTTP 204 on success.
 *  - `POST /api/v1/contributors/merge` — merges source into target contributor.
 *    HTTP 204 on success.
 *  - `POST /api/v1/contributors/{id}/unmerge` — unmerges an alias back out of
 *    the contributor. HTTP 200 with the new [ContributorId] on success.
 *
 * All endpoints require JWT authentication (mounted inside the authenticate block
 * in Application.kt).
 */
private const val AUTH_WALL_REGRESSION_MSG =
    "contributor REST mount reached without a principal — auth wall regression"

fun Route.contributorRoutes(
    contributorService: ContributorService,
    imageHome: Path,
    imageStorage: ImageStorage,
) {
    get<ContributorResources.Detail> { res ->
        when (val result = contributorService.getContributor(ContributorId(res.id))) {
            is AppResult.Success -> {
                val payload = result.data
                if (payload != null) call.respond(payload) else call.respond(HttpStatusCode.NotFound)
            }

            is AppResult.Failure -> {
                call.respondBareAppError(result.error)
            }
        }
    }

    get<ContributorResources.Books> { res ->
        // The scoped service access-filters the listing: a book the caller can't reach is
        // simply absent — existence-preserving, identical to a contributor with no accessible
        // books. ROOT/ADMIN bypass the filter inside the service.
        when (val result = call.scoped(contributorService).listBooksByContributor(ContributorId(res.id))) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    patch<ContributorResources.Detail> { res ->
        val patch = call.receive<ContributorUpdate>()
        when (val result = call.scoped(contributorService).updateContributor(ContributorId(res.id), patch)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    put<ContributorResources.Image> { res ->
        // Store the bytes content-addressed, then persist the path through the scoped service so its
        // internal requireCanEdit gate + revision bump + sync-event publication fire (contributor's
        // canEdit check is not exposed for a pre-buffer gate; the 10 MiB cap bounds the exposure).
        when (val outcome = call.storeMultipartImage("contributors", imageHome, imageStorage)) {
            is ImageUploadOutcome.Rejected -> {
                call.respond(outcome.status, outcome.message)
            }

            is ImageUploadOutcome.Stored -> {
                when (
                    val result =
                        call
                            .scoped(contributorService)
                            .updateContributor(ContributorId(res.id), ContributorUpdate(imagePath = outcome.relPath))
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

    delete<ContributorResources.Detail> { res ->
        when (val result = call.scoped(contributorService).deleteContributor(ContributorId(res.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<ContributorResources.Merge> {
        val body = call.receive<MergeContributorsBody>()
        when (val result = call.scoped(contributorService).mergeContributors(body.source, body.target)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<ContributorResources.Unmerge> { res ->
        val body = call.receive<UnmergeContributorBody>()
        when (val result = call.scoped(contributorService).unmergeContributor(ContributorId(res.id), body.aliasName)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

/**
 * Scopes [service] to the authenticated caller so mutation handlers gate on the caller's
 * `canEdit` flag. Reaching this without a principal is an auth-wall regression.
 */
private fun ApplicationCall.scoped(service: ContributorService): ContributorService {
    val p = userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
    return (service as ContributorServiceImpl).copyWith(PrincipalProvider { p })
}

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
