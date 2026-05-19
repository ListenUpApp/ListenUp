package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.BookResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.plugins.RateLimitBuckets
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST surface for [BookService]. Three endpoints:
 *
 *  - `GET /api/v1/books/{id}` — returns the full [BookSyncPayload] for the
 *    given id. HTTP 200 on success; HTTP 404 when no book with that id exists.
 *    Responds the **unwrapped** value (RESTful convention for the third-party
 *    surface, not the AppResult-wrapped envelope that authRoutes uses).
 *  - `GET /api/v1/books?q=&limit=` — runs a server-side FTS5 query and returns
 *    a [List]<[com.calypsan.listenup.core.BookId]> in rank order.
 *    Rate-limited to 60 req/min per host.
 *  - `GET /api/v1/books/{id}/cover` — serves the book's cover image bytes
 *    (filesystem image or embedded artwork). HTTP 200 with the image on
 *    success, HTTP 404 when the book is absent or has no servable cover.
 *
 * All endpoints require JWT authentication (mounted inside the authenticate
 * block in Application.kt). Cover serving is delegated to [coverResponder].
 */
fun Route.bookRoutes(
    bookService: BookService,
    coverResponder: CoverResponder,
) {
    get<BookResources.Detail> { res ->
        when (val result = bookService.getBook(res.id)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<BookResources.Cover> { res ->
        coverResponder.respondCover(call, res.id)
    }

    rateLimit(RateLimitBuckets.BooksSearch) {
        get<BookResources> { res ->
            when (val result = bookService.searchBooks(res.q ?: "", res.limit)) {
                is AppResult.Success -> call.respond(result.data)
                is AppResult.Failure -> call.respondBareAppError(result.error)
            }
        }
    }
}

/**
 * Responds a bare [AppError] body (no [AppResult] envelope) with the status
 * derived from [AppError.toHttpStatus] and the correlation id stamped from
 * the call id. Used by the book REST surface which follows the third-party
 * RESTful convention of responding the unwrapped error directly.
 */
private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
