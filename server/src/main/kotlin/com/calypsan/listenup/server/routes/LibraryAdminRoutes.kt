package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.LibraryResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.plugins.toHttpStatus
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
    // GET /api/v1/libraries — list all non-deleted libraries
    get<LibraryResources.Collection> {
        when (val result = service.listLibraries()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // POST /api/v1/libraries — create a new library
    post<LibraryResources.Collection> {
        val request = call.receive<CreateLibraryRequest>()
        when (val result = service.createLibrary(request)) {
            is AppResult.Success -> call.respond(HttpStatusCode.Created, result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // GET /api/v1/libraries/setup-status
    get<LibraryResources.SetupStatus> {
        when (val result = service.getSetupStatus()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // GET /api/v1/libraries/browse?path=…
    get<LibraryResources.Browse> { resource ->
        when (val result = service.browseFilesystem(resource.path)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // GET /api/v1/libraries/{id}
    get<LibraryResources.Detail> { resource ->
        when (val result = service.getLibrary(LibraryId(resource.id))) {
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
        when (val result = service.renameLibrary(LibraryId(resource.id), body.name)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // DELETE /api/v1/libraries/{id}
    delete<LibraryResources.Detail> { resource ->
        when (val result = service.deleteLibrary(LibraryId(resource.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // POST /api/v1/libraries/{id}/scan
    post<LibraryResources.Scan> { resource ->
        when (val result = service.scanLibrary(LibraryId(resource.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.Accepted)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // POST /api/v1/libraries/{id}/folders
    post<LibraryResources.Folders> { resource ->
        val body = call.receive<AddFolderBody>()
        when (val result = service.addFolder(LibraryId(resource.id), body.path)) {
            is AppResult.Success -> call.respond(HttpStatusCode.Created, result.data)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // DELETE /api/v1/libraries/folders/{folderId}
    delete<LibraryResources.FolderDetail> { resource ->
        when (val result = service.removeFolder(FolderId(resource.folderId))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondLibraryError(result.error)
        }
    }

    // POST /api/v1/libraries/folders/{folderId}/scan
    post<LibraryResources.FolderScan> { resource ->
        when (val result = service.scanFolder(FolderId(resource.folderId))) {
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

private suspend fun io.ktor.server.application.ApplicationCall.respondLibraryError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
