package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.PlaybackProgressResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.PlaybackProgressServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST surface for [PlaybackProgressService]. Four endpoints:
 *
 *  - `GET /api/v1/playback-progress?limit=N` — all of the caller's positions
 *    (excluding tombstones). Order unspecified; up to [limit] rows, clamped to [1, 500].
 *  - `POST /api/v1/playback-progress/batch` — sparse batch lookup by [BookId].
 *    Body: `List<String>` (book id values). Missing positions are silently absent.
 *  - `GET /api/v1/playback-progress/recently-listened?limit=N` — unfinished positions
 *    ordered by `lastPlayedAt DESC`. Clamped to [1, 100].
 *  - `GET /api/v1/playback-progress/completed?limit=N` — finished positions ordered by
 *    `lastPlayedAt DESC`. Clamped to [1, 500].
 *
 * All endpoints require JWT authentication (mounted inside the authenticate block in
 * Application.kt). Responds with bare types (unwrapped from AppResult) per the
 * third-party REST surface convention.
 */
fun Route.playbackProgressRoutes(playbackProgressService: PlaybackProgressService) {
    get<PlaybackProgressResources.List> { resource ->
        val p = call.userPrincipalOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val scoped = (playbackProgressService as PlaybackProgressServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.listProgress(resource.limit)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareProgressError(result.error)
        }
    }

    post<PlaybackProgressResources.Batch> {
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val bookIds = call.receive<List<String>>().map { BookId(it) }
        val scoped = (playbackProgressService as PlaybackProgressServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.getProgressBatch(bookIds)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareProgressError(result.error)
        }
    }

    get<PlaybackProgressResources.RecentlyListened> { resource ->
        val p = call.userPrincipalOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val scoped = (playbackProgressService as PlaybackProgressServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.getRecentlyListened(resource.limit)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareProgressError(result.error)
        }
    }

    get<PlaybackProgressResources.Completed> { resource ->
        val p = call.userPrincipalOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val scoped = (playbackProgressService as PlaybackProgressServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.getCompletedBooks(resource.limit)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareProgressError(result.error)
        }
    }
}

private suspend fun ApplicationCall.respondBareProgressError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
