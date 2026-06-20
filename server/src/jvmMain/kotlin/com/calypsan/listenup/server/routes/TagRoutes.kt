package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.BookTagsResources
import com.calypsan.listenup.server.routes.resources.TagResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.TagServiceImpl
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

private const val AUTH_WALL_REGRESSION_MSG =
    "tag REST mount reached without a principal — auth wall regression"

/**
 * REST surface for [TagService]. Seven endpoints:
 *
 *  - `GET /api/v1/tags` — all non-deleted tags, ordered by book count desc then name asc,
 *    each with a live [com.calypsan.listenup.api.dto.TagSummary.bookCount].
 *  - `GET /api/v1/tags/by-slug/{slug}` — single tag by URL slug. HTTP 200 on hit,
 *    HTTP 404 when no tag with that slug exists.
 *  - `GET /api/v1/tags/{tagId}/books?limit=N` — book ids tagged with [tagId].
 *    Limit clamped server-side to 1..1000.
 *  - `GET /api/v1/books/{bookId}/tags` — tags applied to a book. HTTP 404 if book absent.
 *  - `POST /api/v1/books/{bookId}/tags` — add a tag to a book (body: raw name string).
 *    Find-or-create semantics; idempotent. HTTP 200 with the tag aggregate.
 *  - `DELETE /api/v1/books/{bookId}/tags/{tagId}` — remove tag from book (soft-delete junction).
 *    HTTP 204 on success.
 *  - `PATCH /api/v1/tags/{tagId}` — rename a tag (body: new name string). HTTP 200 with
 *    updated tag; slug is preserved.
 *  - `DELETE /api/v1/tags/{tagId}` — delete tag and cascade-tombstone all junctions.
 *    HTTP 204 on success.
 *
 * All endpoints require JWT authentication (mounted inside the authenticate block in
 * Application.kt). Responds with bare types (unwrapped from AppResult) per the
 * third-party REST surface convention.
 *
 * The book-keyed read `GET /api/v1/books/{bookId}/tags` is access-gated through
 * [accessPolicy], mirroring the cover route: a member must not learn which tags hang
 * off a book they can't reach. A denied book answers 404 — indistinguishable from an
 * absent book — never 403, which would leak the private book's existence. ROOT/ADMIN
 * bypass the filter. The tag-keyed and mutation routes remain ungated pending the
 * broader tag-permission model.
 */
fun Route.tagRoutes(
    tagService: TagService,
    accessPolicy: BookAccessPolicy,
) {
    // ── Tag-scoped routes ─────────────────────────────────────────────────────

    get<TagResources.List> {
        when (val result = tagService.listTags()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondTagError(result.error)
        }
    }

    get<TagResources.BySlug> { res ->
        when (val result = tagService.getTagBySlug(res.slug)) {
            is AppResult.Success -> {
                val tag = result.data
                if (tag == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found", "slug" to res.slug))
                } else {
                    call.respond(tag)
                }
            }

            is AppResult.Failure -> {
                call.respondTagError(result.error)
            }
        }
    }

    get<TagResources.Books> { res ->
        when (val result = tagService.listBooksForTag(TagId(res.tagId), res.limit)) {
            is AppResult.Success -> call.respond(result.data.map { it.value })
            is AppResult.Failure -> call.respondTagError(result.error)
        }
    }

    patch<TagResources.Detail> { res ->
        val newName = call.receive<String>()
        when (val result = call.scoped(tagService).renameTag(TagId(res.tagId), newName)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondTagError(result.error)
        }
    }

    delete<TagResources.Detail> { res ->
        when (val result = call.scoped(tagService).deleteTag(TagId(res.tagId))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondTagError(result.error)
        }
    }

    // ── Book-scoped tag routes ────────────────────────────────────────────────

    get<BookTagsResources.Collection> { res ->
        // Gate the book's tags by the caller's principal, mirroring the cover route.
        // A denied book answers 404 — the same shape as an absent book — so the
        // response can't probe a private book's existence.
        val p = call.userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
        if (!accessPolicy.canAccess(p.userId.value, p.role, res.parent.bookId)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        when (val result = tagService.listTagsForBook(BookId(res.parent.bookId))) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondTagError(result.error)
        }
    }

    post<BookTagsResources.Collection> { res ->
        val name = call.receive<String>()
        when (val result = call.scoped(tagService).addTagToBook(BookId(res.parent.bookId), name)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondTagError(result.error)
        }
    }

    delete<BookTagsResources.Detail> { res ->
        when (val result = call.scoped(tagService).removeTagFromBook(BookId(res.parent.bookId), TagId(res.tagId))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondTagError(result.error)
        }
    }
}

/**
 * Scopes [service] to the authenticated caller so mutation handlers gate on the caller's
 * `canEdit` flag. Reaching this without a principal is an auth-wall regression.
 */
private fun ApplicationCall.scoped(service: TagService): TagService {
    val p = userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
    return (service as TagServiceImpl).copyWith(PrincipalProvider { p })
}

private suspend fun ApplicationCall.respondTagError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
