package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.PlaybackProgressResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.BookAccessPolicy
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
 *    Requested ids the caller can't reach are filtered out before the lookup, so an
 *    inaccessible book is absent from the response exactly like a book with no
 *    progress — never a distinct status — closing the differential-existence edge.
 *  - `GET /api/v1/playback-progress/recently-listened?limit=N` — unfinished positions
 *    ordered by `lastPlayedAt DESC`. Clamped to [1, 100].
 *  - `GET /api/v1/playback-progress/completed?limit=N` — finished positions ordered by
 *    `lastPlayedAt DESC`. Clamped to [1, 500].
 *
 * All endpoints require JWT authentication (mounted inside the authenticate block in
 * Application.kt). Responds with bare types (unwrapped from AppResult) per the
 * third-party REST surface convention.
 *
 * Progress is already per-user (a caller only ever sees their own positions), so the
 * residual risk is differential existence: a forged inaccessible [BookId] in the batch
 * could behave differently from an accessible one. The batch route closes that edge by
 * filtering the requested ids through [accessPolicy] before the lookup — inaccessible
 * ids drop out and are simply absent from the response, the same shape as a book with no
 * stored progress. ROOT/ADMIN bypass the filter (every requested id is kept).
 */
fun Route.playbackProgressRoutes(
    playbackProgressService: PlaybackProgressService,
    accessPolicy: BookAccessPolicy,
) {
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
        val requested = call.receive<List<String>>()
        // Drop ids the caller can't reach so an inaccessible book is absent from the
        // response exactly like a book with no progress — no differential existence.
        // null = ROOT/ADMIN (unfiltered); they keep every requested id.
        val accessible = accessPolicy.accessibleBookIds(p.userId.value, p.role)
        val bookIds =
            requested
                .filter { accessible == null || it in accessible }
                .map { BookId(it) }
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
