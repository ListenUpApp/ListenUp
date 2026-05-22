package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.Position
import com.calypsan.listenup.api.resources.Prepare
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.PlaybackServiceImpl
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
 * REST surface for [PlaybackService]. Three endpoints:
 *
 *  - `GET /api/v1/playback/prepare/{bookId}` — returns a [com.calypsan.listenup.api.dto.PreparedPlayback]
 *    (signed audio URLs + resume position in one call).
 *  - `GET /api/v1/playback/position/{bookId}` — returns the caller's current
 *    playback position, or 404 when no position exists.
 *  - `POST /api/v1/playback/position/{bookId}` — records the caller's playback
 *    position. Idempotent; `lastPlayedAt`-wins.
 *
 * All endpoints require JWT authentication (mounted inside the authenticate
 * block in Application.kt). Responds bare types (unwrapped from AppResult) per
 * the third-party REST surface convention.
 */
fun Route.playbackRoutes(playbackService: PlaybackService) {
    get<Prepare> { resource ->
        val p = call.userPrincipalOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val scoped = (playbackService as PlaybackServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.prepare(resource.bookId)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<Position> { resource ->
        val p = call.userPrincipalOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val scoped = (playbackService as PlaybackServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.getPosition(resource.bookId)) {
            is AppResult.Success -> {
                val position = result.data
                if (position != null) {
                    call.respond(position)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    post<Position> { resource ->
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val scoped = (playbackService as PlaybackServiceImpl).copyWith(PrincipalProvider { p })
        val body = call.receive<RecordPositionRequest>().copy(bookId = resource.bookId.value)
        when (val result = scoped.recordPosition(body)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
