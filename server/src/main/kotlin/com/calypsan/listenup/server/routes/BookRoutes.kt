package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.BookResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.plugins.RateLimitBuckets
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private const val AUTH_WALL_REGRESSION_MSG =
    "book REST mount reached without a principal — auth wall regression"

/**
 * REST surface for [BookService]. Seven endpoints:
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
 *  - `PATCH /api/v1/books/{id}` — applies a [BookUpdate] patch to the book.
 *    HTTP 204 on success.
 *  - `PUT /api/v1/books/{id}/contributors` — replaces the full contributor list
 *    for a book (body: JSON array of [BookContributorInput]). HTTP 204 on success.
 *  - `PUT /api/v1/books/{id}/series` — replaces the full series list for a book
 *    (body: JSON array of [BookSeriesInput]). HTTP 204 on success.
 *  - `DELETE /api/v1/books/{id}/cover` — removes the book's cover image.
 *    HTTP 204 on success.
 *
 * All endpoints require JWT authentication (mounted inside the authenticate
 * block in Application.kt). Cover serving is delegated to [coverResponder].
 *
 * The cover and search reads are access-gated through [accessPolicy] just like
 * `getBook`: a member must not fetch the artwork of — or surface an FTS id for —
 * a book they can't reach. A denied cover answers 404 (indistinguishable from an
 * absent/cover-less book — never 403, which would leak existence).
 */
internal fun Route.bookRoutes(
    bookService: BookService,
    coverResponder: CoverResponder,
    accessPolicy: BookAccessPolicy,
) {
    get<BookResources.Detail> { res ->
        // getBook is access-gated by the caller's principal — scope the service to
        // the authenticated user per-request, mirroring the RPC mount in RpcRoutes.
        val p = call.userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
        val scoped = (bookService as BookServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.getBook(res.id)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    get<BookResources.Cover> { res ->
        // Gate the cover (book content) by the caller's principal, mirroring getBook.
        // A denied book answers 404 — the same shape respondCover gives for an absent
        // or cover-less book — so the response can't probe a private book's existence.
        val p = call.userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
        if (!accessPolicy.canAccess(p.userId.value, p.role, res.id.value)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        coverResponder.respondCover(call, res.id)
    }

    rateLimit(RateLimitBuckets.BooksSearch) {
        get<BookResources> { res ->
            // searchBooks is access-gated by the caller's principal — scope the service to
            // the authenticated user per-request, mirroring the Detail (getBook) handler.
            val p = call.userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
            val scoped = (bookService as BookServiceImpl).copyWith(PrincipalProvider { p })
            when (val result = scoped.searchBooks(res.q ?: "", res.limit)) {
                is AppResult.Success -> call.respond(result.data)
                is AppResult.Failure -> call.respondBareAppError(result.error)
            }
        }
    }

    patch<BookResources.Detail> { res ->
        val patch = call.receive<BookUpdate>()
        when (val result = bookService.updateBook(res.id, patch)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    put<BookResources.Contributors> { res ->
        val contributors = call.receive<List<BookContributorInput>>()
        when (val result = bookService.setBookContributors(res.id, contributors)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    put<BookResources.Series> { res ->
        val series = call.receive<List<BookSeriesInput>>()
        when (val result = bookService.setBookSeries(res.id, series)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    put<BookResources.Genres> { res ->
        val genres = call.receive<List<BookGenreInput>>()
        when (val result = bookService.setBookGenres(res.id, genres)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }

    delete<BookResources.Cover> { res ->
        when (val result = bookService.deleteBookCover(res.id)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBareAppError(result.error)
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
