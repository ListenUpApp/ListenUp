package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.dto.CreateCollectionBody
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.ShareCollectionBody
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.CollectionResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.api.CollectionServiceImpl
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
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get as getPath
import io.ktor.server.routing.post as postPath

/**
 * Admin-only inbox endpoints. The inbox is a system collection; its read/release flow is
 * exposed through [CollectionServiceImpl]'s public methods (deliberately not on the
 * `@Rpc CollectionService` contract) so admins can triage freshly-ingested books.
 *
 *  - `GET  /api/v1/admin/collections/inbox?libraryId=<id>`     — live book ids in the inbox
 *  - `POST /api/v1/admin/collections/inbox/release?libraryId=<id>`
 *    (body: `{ "<bookId>": ["<collectionId>", …] }`) — release books out of the inbox into
 *    their assigned target collections
 *
 * The service enforces the ROOT/ADMIN gate (returning `CollectionError.Forbidden` otherwise);
 * the route only binds the principal and translates the typed failure to HTTP.
 */
fun Route.collectionAdminRoutes(collectionService: CollectionService) {
    getPath("/api/v1/admin/collections/inbox") {
        val service = call.scoped(collectionService) ?: return@getPath
        val libraryId =
            call.request.queryParameters["libraryId"]
                ?: return@getPath call.respond(HttpStatusCode.BadRequest)
        when (val result = service.listInbox(libraryId)) {
            is AppResult.Success -> call.respond(result.data.map { it.value })
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    postPath("/api/v1/admin/collections/inbox/release") {
        val service = call.scoped(collectionService) ?: return@postPath
        val libraryId =
            call.request.queryParameters["libraryId"]
                ?: return@postPath call.respond(HttpStatusCode.BadRequest)
        val assignments = call.receive<Map<String, List<String>>>()
        when (val result = service.releaseBooks(libraryId, assignments)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }
}

/**
 * Returns [collectionService] scoped to this call's authenticated principal, or null after
 * responding 401 when no principal is present (an auth-wall regression — these routes mount
 * inside `authenticate(JWT_PROVIDER)`).
 */
private suspend fun ApplicationCall.scoped(collectionService: CollectionService): CollectionServiceImpl? {
    val principal = userPrincipalOrNull()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return (collectionService as CollectionServiceImpl).copyWith(PrincipalProvider { principal })
}

private suspend fun ApplicationCall.respondCollectionError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
