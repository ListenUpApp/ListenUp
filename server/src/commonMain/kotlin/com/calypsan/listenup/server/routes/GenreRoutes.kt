package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.server.routes.resources.CreateGenreBody
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.server.routes.resources.MapUnmappedBody
import com.calypsan.listenup.server.routes.resources.MergeGenresBody
import com.calypsan.listenup.server.routes.resources.MoveGenreBody
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.GenreResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.api.GenreServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
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
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST surface for [GenreService]. Eleven endpoints mirroring the RPC contract:
 *
 *  - `GET    /api/v1/genres`                          — list all live genres + bookCount
 *  - `POST   /api/v1/genres`                          — create a genre under an optional parent
 *  - `GET    /api/v1/genres/{id}`                     — single genre by id (200 / 404)
 *  - `PATCH  /api/v1/genres/{id}`                     — patch fields on a genre
 *  - `DELETE /api/v1/genres/{id}`                     — soft-delete + cascade junction
 *  - `GET    /api/v1/genres/{id}/children`            — direct children only
 *  - `GET    /api/v1/genres/{id}/books`               — accessible books linked to the genre (+ subtree opt)
 *  - `POST   /api/v1/genres/{id}/move`                — reparent the subtree
 *  - `POST   /api/v1/genres/merge`                    — merge source into target
 *  - `GET    /api/v1/genres/unmapped`                 — aggregate pending-string queue
 *  - `POST   /api/v1/genres/unmapped/map`             — bind a raw string to a genre
 *
 * All endpoints require JWT authentication (mounted inside the authenticate block in
 * Application.kt). Responds with bare types (unwrapped from AppResult) per the
 * third-party REST surface convention. Split into two sub-route-builders keeps each
 * under the cognitive-complexity ceiling.
 *
 * The book-keyed read `GET /api/v1/genres/{id}/books` is access-gated by the scoped
 * service (via `BookAccessPolicy`): the returned book ids are filtered to those the
 * caller can reach, so an inaccessible book is simply absent — existence-preserving,
 * identical to a genre that has no accessible books. ROOT/ADMIN bypass the filter. The
 * remaining routes are not book-keyed and stay ungated pending the broader
 * genre-permission model.
 */
private const val AUTH_WALL_REGRESSION_MSG =
    "genre REST mount reached without a principal — auth wall regression"

fun Route.genreRoutes(genreService: GenreService) {
    genreCollectionRoutes(genreService)
    genreDetailRoutes(genreService)
}

/** Top-level + merge + unmapped routes. */
private fun Route.genreCollectionRoutes(genreService: GenreService) {
    get<GenreResources> {
        when (val result = genreService.listGenres()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    post<GenreResources> {
        val body = call.receive<CreateGenreBody>()
        when (val result = call.scoped(genreService).createGenre(body.parentId, body.name, body.sortOrder)) {
            is AppResult.Success -> call.respond(HttpStatusCode.Created, result.data)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    post<GenreResources.Merge> {
        val body = call.receive<MergeGenresBody>()
        when (val result = call.scoped(genreService).mergeGenres(body.source, body.target)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    get<GenreResources.Unmapped> {
        when (val result = genreService.listUnmappedStrings()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    post<GenreResources.Unmapped.Map> {
        val body = call.receive<MapUnmappedBody>()
        when (val result = call.scoped(genreService).mapUnmappedToGenre(body.rawString, body.genreId)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }
}

/** Per-genre routes under `/api/v1/genres/{id}`. */
private fun Route.genreDetailRoutes(genreService: GenreService) {
    get<GenreResources.Detail> { res ->
        when (val result = genreService.getGenre(GenreId(res.id))) {
            is AppResult.Success -> {
                val payload = result.data
                if (payload == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found", "id" to res.id))
                } else {
                    call.respond(payload)
                }
            }

            is AppResult.Failure -> {
                call.respondGenreError(result.error)
            }
        }
    }

    patch<GenreResources.Detail> { res ->
        val patch = call.receive<GenreUpdate>()
        when (val result = call.scoped(genreService).updateGenre(GenreId(res.id), patch)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    delete<GenreResources.Detail> { res ->
        when (val result = call.scoped(genreService).deleteGenre(GenreId(res.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    get<GenreResources.Detail.Children> { res ->
        when (val result = genreService.getGenreChildren(GenreId(res.parent.id))) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    get<GenreResources.Detail.Books> { res ->
        // The scoped service access-filters the browse: an inaccessible book is simply absent —
        // existence-preserving, identical to a genre with no accessible books. ROOT/ADMIN bypass
        // the filter inside the service.
        val result =
            call.scoped(genreService).browseBooks(
                genreId = GenreId(res.parent.id),
                includeDescendants = res.includeDescendants,
                limit = res.limit,
            )
        when (result) {
            is AppResult.Success -> {
                call.respond(result.data.map { it.value })
            }

            is AppResult.Failure -> {
                call.respondGenreError(result.error)
            }
        }
    }

    post<GenreResources.Detail.Move> { res ->
        val body = call.receive<MoveGenreBody>()
        when (val result = call.scoped(genreService).moveGenre(GenreId(res.parent.id), body.newParentId)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }
}

/**
 * Scopes [service] to the authenticated caller so mutation handlers gate on the caller's
 * `canEdit` flag. Reaching this without a principal is an auth-wall regression.
 */
private fun ApplicationCall.scoped(service: GenreService): GenreService {
    val p = userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
    return (service as GenreServiceImpl).copyWith(PrincipalProvider { p })
}

private suspend fun ApplicationCall.respondGenreError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
