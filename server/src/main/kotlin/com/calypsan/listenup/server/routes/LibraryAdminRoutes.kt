package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.LibraryResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
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
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/**
 * REST mirror of [LibraryAdminService]. All routes live under `/api/v1/libraries`.
 *
 * All routes require JWT authentication (mounted inside the `authenticate` block
 * in `Application.kt`). Read operations use `GET`; mutations use `POST`, `PATCH`,
 * or `DELETE`. Failures are mapped to their canonical HTTP status codes via
 * [AppError.toHttpStatus].
 *
 * Library structure is admin territory: each handler scopes the service to the
 * authenticated caller via [scopedToCaller] so [LibraryAdminServiceImpl]'s admin
 * gate sees the real role. A member hitting a mutating route (or `browse`) gets a
 * `403`; the read routes stay open.
 *
 * Route mapping:
 * - `GET  /api/v1/libraries`                         → [LibraryAdminService.listLibraries]
 * - `POST /api/v1/libraries`                         → [LibraryAdminService.createLibrary]
 * - `GET  /api/v1/libraries/setup-status`            → [LibraryAdminService.getSetupStatus]
 * - `GET  /api/v1/libraries/browse?path=…`           → [LibraryAdminService.browseFilesystem]
 * - `GET  /api/v1/libraries/{id}`                    → [LibraryAdminService.getLibrary]
 * - `PATCH /api/v1/libraries/{id}`                   → [LibraryAdminService.renameLibrary]
 * - `DELETE /api/v1/libraries/{id}`                  → [LibraryAdminService.deleteLibrary]
 * - `POST /api/v1/libraries/{id}/scan`               → [LibraryAdminService.scanLibrary]
 * - `POST /api/v1/libraries/{id}/folders`            → [LibraryAdminService.addFolder]
 * - `DELETE /api/v1/libraries/folders/{folderId}`    → [LibraryAdminService.removeFolder]
 * - `POST /api/v1/libraries/folders/{folderId}/scan` → [LibraryAdminService.scanFolder]
 */
fun Route.libraryAdminRoutes(service: LibraryAdminService) {
    libraryCollectionRoutes(service)
    libraryDetailRoutes(service)
    libraryFolderRoutes(service)
}

private fun Route.libraryCollectionRoutes(service: LibraryAdminService) {
    // GET /api/v1/libraries — list all non-deleted libraries
    get<LibraryResources.Collection> {
        when (val result = call.scopedToCaller(service).listLibraries()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // POST /api/v1/libraries — create a new library
    post<LibraryResources.Collection> {
        val request = call.receive<CreateLibraryRequest>()
        when (val result = call.scopedToCaller(service).createLibrary(request)) {
            is AppResult.Success -> call.respond(HttpStatusCode.Created, result.data)
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
}

private fun Route.libraryDetailRoutes(service: LibraryAdminService) {
    // GET /api/v1/libraries/{id}
    get<LibraryResources.Detail> { resource ->
        when (val result = call.scopedToCaller(service).getLibrary(LibraryId(resource.id))) {
            is AppResult.Success -> {
                val library = result.data
                if (library != null) call.respond(library) else call.respond(HttpStatusCode.NoContent)
            }

            is AppResult.Failure -> {
                call.respondLibraryError(result.error)
            }
        }
    }

    // PATCH /api/v1/libraries/{id}
    patch<LibraryResources.Detail> { resource ->
        val body = call.receive<RenameLibraryBody>()
        when (val result = call.scopedToCaller(service).renameLibrary(LibraryId(resource.id), body.name)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // DELETE /api/v1/libraries/{id}
    delete<LibraryResources.Detail> { resource ->
        when (val result = call.scopedToCaller(service).deleteLibrary(LibraryId(resource.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // POST /api/v1/libraries/{id}/scan
    post<LibraryResources.Scan> { resource ->
        when (val result = call.scopedToCaller(service).scanLibrary(LibraryId(resource.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.Accepted)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }
}

private fun Route.libraryFolderRoutes(service: LibraryAdminService) {
    // POST /api/v1/libraries/{id}/folders
    post<LibraryResources.Folders> { resource ->
        val body = call.receive<AddFolderBody>()
        when (val result = call.scopedToCaller(service).addFolder(LibraryId(resource.id), body.path)) {
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

/** Request body for [LibraryAdminService.renameLibrary]. */
@Serializable
private data class RenameLibraryBody(
    val name: String,
)

/** Request body for [LibraryAdminService.addFolder]. */
@Serializable
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
