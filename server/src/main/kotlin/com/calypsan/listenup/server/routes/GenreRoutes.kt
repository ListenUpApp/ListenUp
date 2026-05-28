package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.dto.CreateGenreBody
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.dto.MapUnmappedBody
import com.calypsan.listenup.api.dto.MergeGenresBody
import com.calypsan.listenup.api.dto.MoveGenreBody
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.GenreResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
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
 *  - `GET    /api/v1/genres/{id}/books`               — books linked to the genre (+ subtree opt)
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
 * // TODO: gate by user permissions when Multi-user lands
 */
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
        when (val result = genreService.createGenre(body.parentId, body.name, body.sortOrder)) {
            is AppResult.Success -> call.respond(HttpStatusCode.Created, result.data)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    post<GenreResources.Merge> {
        val body = call.receive<MergeGenresBody>()
        when (val result = genreService.mergeGenres(body.source, body.target)) {
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
        when (val result = genreService.mapUnmappedToGenre(body.rawString, body.genreId)) {
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
        when (val result = genreService.updateGenre(GenreId(res.id), patch)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    delete<GenreResources.Detail> { res ->
        when (val result = genreService.deleteGenre(GenreId(res.id))) {
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
        val result =
            genreService.browseBooks(
                genreId = GenreId(res.parent.id),
                includeDescendants = res.includeDescendants,
                limit = res.limit,
            )
        when (result) {
            is AppResult.Success -> call.respond(result.data.map { it.value })
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }

    post<GenreResources.Detail.Move> { res ->
        val body = call.receive<MoveGenreBody>()
        when (val result = genreService.moveGenre(GenreId(res.parent.id), body.newParentId)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondGenreError(result.error)
        }
    }
}

private suspend fun ApplicationCall.respondGenreError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
