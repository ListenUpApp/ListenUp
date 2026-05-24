package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.SearchResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST mirror of [SearchService]. Mounts under `/api/v1/search/`.
 *
 * All routes require JWT authentication (mounted inside the `authenticate`
 * block in `Application.kt`). Responds with the bare [SearchResults] value
 * on success, consistent with the third-party REST surface convention in this
 * server. A blank query returns an empty-list envelope with HTTP 200 rather
 * than a 400 — search is always safe to call.
 */
fun Route.searchRoutes(searchService: SearchService) {
    get<SearchResources.All> { resource ->
        when (val result = searchService.search(resource.query, resource.limit)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
