package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.dto.CreateCollectionBody
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.ShareCollectionBody
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.CollectionResources
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
 * REST surface for [CollectionService]. Mirrors the twelve user-facing RPC methods:
 *
 *  - `GET    /api/v1/collections`                          — list accessible collections
 *  - `POST   /api/v1/collections` (CreateCollectionBody)   — create a collection
 *  - `GET    /api/v1/collections/{id}`                     — single collection summary
 *  - `PATCH  /api/v1/collections/{id}` (raw name string)   — rename a collection
 *  - `DELETE /api/v1/collections/{id}`                     — delete a collection
 *  - `GET    /api/v1/collections/{id}/books?limit=N`       — member book ids
 *  - `PUT    /api/v1/collections/{id}/books/{bookId}`      — add a book
 *  - `DELETE /api/v1/collections/{id}/books/{bookId}`      — remove a book
 *  - `PUT    /api/v1/collections/book/{bookId}` (id list)  — replace-set a book's collections
 *  - `GET    /api/v1/collections/{id}/shares`              — list shares
 *  - `POST   /api/v1/collections/{id}/shares` (ShareBody)  — share with a user
 *  - `PATCH  /api/v1/collections/{id}/shares/{userId}` (permission) — update a share
 *  - `DELETE /api/v1/collections/{id}/shares/{userId}`     — revoke a share
 *
 * RPC is the first-class surface; these exist so the same operations are reachable
 * over plain REST. Every handler binds the request to the authenticated principal via
 * [CollectionServiceImpl.copyWith] (the Koin singleton carries an unscoped placeholder),
 * mirroring the RPC registration. Responds with bare types (unwrapped from AppResult)
 * per the third-party REST surface convention.
 *
 * All endpoints require JWT authentication (mounted inside the authenticate block in
 * Application.kt).
 */
fun Route.collectionRoutes(collectionService: CollectionService) {
    get<CollectionResources.List> {
        val service = call.scoped(collectionService) ?: return@get
        when (val result = service.listCollections()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    post<CollectionResources.List> {
        val service = call.scoped(collectionService) ?: return@post
        val body = call.receive<CreateCollectionBody>()
        when (val result = service.createCollection(body.libraryId, body.name)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    get<CollectionResources.Detail> { res ->
        val service = call.scoped(collectionService) ?: return@get
        when (val result = service.getCollection(CollectionId(res.id))) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    patch<CollectionResources.Detail> { res ->
        val service = call.scoped(collectionService) ?: return@patch
        val newName = call.receive<String>()
        when (val result = service.renameCollection(CollectionId(res.id), newName)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    delete<CollectionResources.Detail> { res ->
        val service = call.scoped(collectionService) ?: return@delete
        when (val result = service.deleteCollection(CollectionId(res.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    collectionBookRoutes(collectionService)
    collectionShareRoutes(collectionService)
}

/** Book-membership routes — split out to keep [collectionRoutes] under the complexity ceiling. */
private fun Route.collectionBookRoutes(collectionService: CollectionService) {
    get<CollectionResources.Books> { res ->
        val service = call.scoped(collectionService) ?: return@get
        when (val result = service.listCollectionBooks(CollectionId(res.id), res.limit)) {
            is AppResult.Success -> call.respond(result.data.map { it.value })
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    put<CollectionResources.Detail.Book> { res ->
        val service = call.scoped(collectionService) ?: return@put
        when (val result = service.addBookToCollection(CollectionId(res.parent.id), BookId(res.bookId))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    delete<CollectionResources.Detail.Book> { res ->
        val service = call.scoped(collectionService) ?: return@delete
        when (val result = service.removeBookFromCollection(CollectionId(res.parent.id), BookId(res.bookId))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    put<CollectionResources.BookCollections> { res ->
        val service = call.scoped(collectionService) ?: return@put
        val collectionIds = call.receive<List<String>>().map { CollectionId(it) }
        when (val result = service.setBookCollections(BookId(res.bookId), collectionIds)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }
}

/** Share-management routes — split out to keep [collectionRoutes] under the complexity ceiling. */
private fun Route.collectionShareRoutes(collectionService: CollectionService) {
    get<CollectionResources.Shares> { res ->
        val service = call.scoped(collectionService) ?: return@get
        when (val result = service.listShares(CollectionId(res.id))) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    post<CollectionResources.Shares> { res ->
        val service = call.scoped(collectionService) ?: return@post
        val body = call.receive<ShareCollectionBody>()
        when (val result = service.shareCollection(CollectionId(res.id), body.sharedWithUserId, body.permission)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    patch<CollectionResources.ShareDetail> { res ->
        val service = call.scoped(collectionService) ?: return@patch
        val permission = call.receive<SharePermission>()
        when (val result = service.updateShare(CollectionId(res.id), res.userId, permission)) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }

    delete<CollectionResources.ShareDetail> { res ->
        val service = call.scoped(collectionService) ?: return@delete
        when (val result = service.revokeShare(CollectionId(res.id), res.userId)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondCollectionError(result.error)
        }
    }
}

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
