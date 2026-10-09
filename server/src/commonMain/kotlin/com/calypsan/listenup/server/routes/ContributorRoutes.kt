package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.server.routes.resources.ContributorResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask
import com.calypsan.listenup.server.plugins.respondAppError
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.io.files.Path

/**
 * REST surface for [ContributorService]. One endpoint:
 *
 *  - `PUT /api/v1/contributors/{id}/image` — uploads a contributor photo,
 *    content-addressed under [imageHome], then applies the resulting path via
 *    a [ContributorUpdate] patch through the scoped service (so `canEdit`,
 *    revision bump, and sync-event publication all fire). HTTP 204 on
 *    success. The edit permission is checked before the body is read, so a
 *    refused caller writes nothing. A stored file is never deleted here: it is
 *    content-addressed, so another row may already name it, and
 *    [OrphanImageCleanupTask] reclaims any file no row names.
 *
 * Every other [ContributorService] operation (get, list books, patch,
 * delete, merge, unmerge) is RPC-only — this file used to also mirror a
 * merge/unmerge REST pair, but those handlers were never registered; the
 * image upload above is the only route this file actually mounts.
 *
 * Requires JWT authentication (mounted inside the authenticate block in
 * Application.kt).
 */
private const val AUTH_WALL_REGRESSION_MSG =
    "contributor REST mount reached without a principal — auth wall regression"

fun Route.contributorRoutes(
    contributorService: ContributorService,
    imageHome: Path,
    imageStorage: ImageStorage,
) {
    put<ContributorResources.Image> { res ->
        val service = call.scoped(contributorService)
        // Gate BEFORE reading the body: a refused caller must neither make the server buffer 10 MiB
        // nor write a file. The update below re-gates (with the revision bump and sync event).
        service.checkCanEdit()?.let { return@put respondAppError(call, it) }
        val outcome =
            storeMultipartImage(
                call = call,
                subdir = "contributors",
                imageHome = imageHome,
                imageStorage = imageStorage,
            )
        when (outcome) {
            is ImageUploadOutcome.Rejected -> {
                call.respond(outcome.status, outcome.message)
            }

            is ImageUploadOutcome.Stored -> {
                when (
                    val result =
                        service.updateContributor(ContributorId(res.id), ContributorUpdate(imagePath = outcome.relPath))
                ) {
                    is AppResult.Success -> {
                        call.respond(HttpStatusCode.NoContent)
                    }

                    is AppResult.Failure -> {
                        // Never delete the stored file: it is content-addressed, so another row may
                        // already name the same bytes. An unnamed file is the orphan sweep's to reclaim.
                        respondAppError(call, result.error)
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
private fun ApplicationCall.scoped(service: ContributorService): ContributorServiceImpl {
    val p = userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
    return (service as ContributorServiceImpl).copyWith(PrincipalProvider { p })
}
