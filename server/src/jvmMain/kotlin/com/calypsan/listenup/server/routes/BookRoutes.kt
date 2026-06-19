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
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.plugins.RateLimitBuckets
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get as routingGet
import io.ktor.utils.io.toByteArray

private const val AUTH_WALL_REGRESSION_MSG =
    "book REST mount reached without a principal — auth wall regression"

/** Maximum accepted cover image size for the pre-buffer Content-Length check. Mirrors BooksModule. */
private const val COVER_MAX_BYTES = 10L * 1024 * 1024

/**
 * REST surface for [BookService]. Eight endpoints:
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
 *  - `PUT /api/v1/books/{id}/cover` — uploads a replacement cover image (multipart).
 *    Gated on the `canEdit` permission flag; ROOT/ADMIN pass implicitly. HTTP 204 on
 *    success; 403 when the caller lacks `canEdit`; 413 when the part exceeds 10 MiB;
 *    422 when the bytes carry no recognised image magic number.
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
        call.respondGatedCover(res.id, accessPolicy, coverResponder)
    }

    // The KMP/mobile client downloads covers from /api/v1/covers/{bookId} (the Go-era URL).
    // Serve the same access-gated bytes there: the books-nested route alone left every client
    // cover request 404'ing, so covers never rendered.
    routingGet("/api/v1/covers/{id}") {
        val id = call.parameters["id"] ?: return@routingGet call.respond(HttpStatusCode.BadRequest)
        call.respondGatedCover(BookId(id), accessPolicy, coverResponder)
    }

    put<BookResources.Cover> { res ->
        val p = call.userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
        val scoped = (bookService as BookServiceImpl).copyWith(PrincipalProvider { p })
        call.handleCoverUpload(res.id, scoped)
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

/**
 * Reads the multipart cover upload, guards the part size against [COVER_MAX_BYTES] before buffering,
 * then delegates to [BookServiceImpl.setBookCover]. Returns 403 when the caller lacks `canEdit`
 * (checked BEFORE buffering the body to avoid forcing the server to buffer up to 10 MiB for an
 * unauthorized request), 413 when the declared part size exceeds the cap, 400 when no file part
 * is found, 422 when the bytes fail image validation, and 204 on success.
 *
 * [BookServiceImpl.setBookCover] retains its own `requireCanEdit()` call as defense-in-depth —
 * the early check here short-circuits before any body buffering occurs.
 *
 * Extracted from the [bookRoutes] function body to keep cyclomatic complexity within the project
 * threshold.
 */
private suspend fun ApplicationCall.handleCoverUpload(
    bookId: BookId,
    service: BookServiceImpl,
) {
    // Gate canEdit BEFORE buffering — an unauthorized caller must not force the server to
    // buffer up to 10 MiB of multipart body before receiving a 403.
    service.checkCanEdit()?.let { return respondBareAppError(it) }

    var bytes: ByteArray? = null
    var declared = ContentType.Application.OctetStream.toString()
    var oversized = false
    receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem && bytes == null) {
            val declaredLength = part.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > COVER_MAX_BYTES) {
                part.release()
                oversized = true
                return@forEachPart
            }
            declared = part.contentType?.toString() ?: declared
            bytes = part.provider().toByteArray()
        }
        part.release()
    }
    if (oversized) return respond(HttpStatusCode.PayloadTooLarge)
    val data = bytes ?: return respond(HttpStatusCode.BadRequest, "missing file part")
    try {
        when (val result = service.setBookCover(bookId, data, declared)) {
            is AppResult.Success -> respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> respondBareAppError(result.error)
        }
    } catch (e: ImageStore.InvalidImageException) {
        respond(HttpStatusCode.UnprocessableEntity, e.message ?: "invalid image")
    }
}

/**
 * Serves the access-gated cover for [bookId]. A book the caller can't reach answers 404 — the same
 * shape [CoverResponder] gives for an absent or cover-less book — so it never leaks a private book's
 * existence. Shared by `GET /api/v1/books/{id}/cover` and the `GET /api/v1/covers/{id}` client alias.
 */
private suspend fun ApplicationCall.respondGatedCover(
    bookId: BookId,
    accessPolicy: BookAccessPolicy,
    coverResponder: CoverResponder,
) {
    val p = userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
    if (!accessPolicy.canAccess(p.userId.value, p.role, bookId.value)) {
        respond(HttpStatusCode.NotFound)
        return
    }
    coverResponder.respondCover(this, bookId)
}
