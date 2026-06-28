package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.LibraryResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * REST mirror of [LibraryAdminService]. All routes live under `/api/v1/libraries`.
 *
 * All routes require JWT authentication (mounted inside the `authenticate` block
 * in `Application.kt`). Read operations use `GET`; mutations use `POST` or `DELETE`.
 * Failures are mapped to their canonical HTTP status codes via [AppError.toHttpStatus].
 *
 * Library structure is admin territory: each handler scopes the service to the
 * authenticated caller via [scopedToCaller] so [LibraryAdminServiceImpl]'s admin
 * gate sees the real role. A member hitting a mutating route (or `browse`) gets a
 * `403`; the read routes stay open.
 *
 * Route mapping:
 * - `GET  /api/v1/libraries`                         → [LibraryAdminService.getLibrary]
 * - `GET  /api/v1/libraries/setup-status`            → [LibraryAdminService.getSetupStatus]
 * - `GET  /api/v1/libraries/browse?path=…`           → [LibraryAdminService.browseFilesystem]
 * - `POST /api/v1/libraries/folders`                 → [LibraryAdminService.addFolder]
 * - `POST /api/v1/libraries/scan`                    → [LibraryAdminService.scanLibrary]
 * - `DELETE /api/v1/libraries/folders/{folderId}`    → [LibraryAdminService.removeFolder]
 * - `POST /api/v1/libraries/folders/{folderId}/scan` → [LibraryAdminService.scanFolder]
 */
fun Route.libraryAdminRoutes(service: LibraryAdminService) {
    libraryCollectionRoutes(service)
    libraryFolderRoutes(service)
}

private fun Route.libraryCollectionRoutes(service: LibraryAdminService) {
    // GET /api/v1/libraries — fetch THE library
    get<LibraryResources.Collection> {
        when (val result = call.scopedToCaller(service).getLibrary()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // GET /api/v1/libraries/setup-status
    get<LibraryResources.SetupStatus> {
        when (val result = call.scopedToCaller(service).getSetupStatus()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // GET /api/v1/libraries/browse?path=…
    get<LibraryResources.Browse> { resource ->
        when (val result = call.scopedToCaller(service).browseFilesystem(resource.path)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // POST /api/v1/libraries/scan — trigger a full scan of THE library
    post<LibraryResources.Scan> {
        when (val result = call.scopedToCaller(service).scanLibrary()) {
            is AppResult.Success -> call.respond(HttpStatusCode.Accepted)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }
}

private fun Route.libraryFolderRoutes(service: LibraryAdminService) {
    // POST /api/v1/libraries/folders — add a folder to THE library
    post<LibraryResources.Folders> {
        val body = call.receive<AddFolderBody>()
        when (val result = call.scopedToCaller(service).addFolder(body.path)) {
            is AppResult.Success -> call.respond(HttpStatusCode.Created, result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // DELETE /api/v1/libraries/folders/{folderId}
    delete<LibraryResources.FolderDetail> { resource ->
        when (val result = call.scopedToCaller(service).removeFolder(FolderId(resource.folderId))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // POST /api/v1/libraries/folders/{folderId}/scan
    post<LibraryResources.FolderScan> { resource ->
        when (val result = call.scopedToCaller(service).scanFolder(FolderId(resource.folderId))) {
            is AppResult.Success -> call.respond(HttpStatusCode.Accepted)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }
}

/** Request body for [LibraryAdminService.addFolder]. */
@Serializable
@SerialName("AddFolderBody")
private data class AddFolderBody(
    val path: String,
)

private const val AUTH_WALL_REGRESSION_MSG =
    "library admin REST mount reached without a principal — auth wall regression"

/**
 * Scopes [service] to the authenticated caller of the current request so the admin
 * gate in [LibraryAdminServiceImpl] sees the real role. The route is mounted inside
 * the JWT `authenticate` block, so an absent principal is an auth-wall regression.
 */
private fun io.ktor.server.application.ApplicationCall.scopedToCaller(
    service: LibraryAdminService,
): LibraryAdminService {
    val p = userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
    return (service as LibraryAdminServiceImpl).copyWith(PrincipalProvider { p })
}

private suspend fun io.ktor.server.application.ApplicationCall.respondLibraryError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
