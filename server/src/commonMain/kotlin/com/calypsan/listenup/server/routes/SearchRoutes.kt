package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.dto.SearchFilters
import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchSort
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.SearchResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
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
 *
 * Search is access-gated by the caller's principal — results and facet counts both
 * exclude books the caller can't reach — so the service is scoped to the authenticated
 * user per-request, mirroring the RPC mount in `RpcRoutes`.
 */
private const val AUTH_WALL_REGRESSION_MSG =
    "search REST mount reached without a principal — auth wall regression"

fun Route.searchRoutes(searchService: SearchService) {
    get<SearchResources.All> { resource ->
        val p = call.userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
        val scoped = (searchService as SearchServiceImpl).copyWith(PrincipalProvider { p })
        val q =
            SearchQuery(
                text = resource.query,
                limit = resource.limit,
                filters = searchFiltersFrom(resource),
                sort = searchSortFrom(resource.sort),
            )
        when (val result = scoped.search(q)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

private fun searchFiltersFrom(r: SearchResources.All): SearchFilters? {
    val slugs =
        r.genreSlugs
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    val filters =
        SearchFilters(
            genreSlugs = slugs,
            genrePath = r.genrePath?.takeIf { it.isNotBlank() },
            durationMinSeconds = r.durationMin,
            durationMaxSeconds = r.durationMax,
            yearMin = r.yearMin,
            yearMax = r.yearMax,
        )
    return filters.takeIf { it.isActive }
}

private fun searchSortFrom(s: String?): SearchSort =
    when (s?.lowercase()) {
        "title" -> SearchSort.Title
        "recent" -> SearchSort.Recent
        "duration" -> SearchSort.Duration
        else -> SearchSort.Relevance
    }

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
