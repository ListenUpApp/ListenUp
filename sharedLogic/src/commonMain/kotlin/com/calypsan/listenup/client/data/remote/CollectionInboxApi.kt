package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.suspendRunCatching
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody

/**
 * Admin-internal REST client for the collection inbox (Collections-1b admin routes).
 *
 * These two endpoints are deliberately **not** on the `@Rpc CollectionService`
 * contract — they are admin-only triage operations exposed over REST:
 *  - `GET  /api/v1/admin/collections/inbox?libraryId=<id>` → the live book ids in the inbox.
 *  - `POST /api/v1/admin/collections/inbox/release?libraryId=<id>` with a
 *    `{ "<bookId>": ["<collectionId>", …] }` body → releases books out of the inbox
 *    into their assigned target collections (empty target list = released as public).
 *
 * The routes return raw JSON bodies (a `List<String>` and a `204 No Content`), not the
 * `ApiResponse` envelope, so the methods use [suspendRunCatching] directly rather than
 * the envelope-shaped [apiCall] helpers. The server enforces the ROOT/ADMIN gate.
 */
internal interface CollectionInboxApiContract {
    /** Returns the live (unreleased) book ids in the inbox for [libraryId]. */
    suspend fun listInbox(libraryId: String): AppResult<List<String>>

    /**
     * Releases the books keyed in [assignments] out of the inbox. Each entry maps a
     * book id to the collection ids it should be added to on release (an empty list
     * releases the book as publicly visible).
     */
    suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit>
}

/** Ktor implementation of [CollectionInboxApiContract] over the 1b admin REST routes. */
@NonRpcTransport(
    NonRpcReason.THIRD_PARTY_REST,
    justification = "Admin inbox triage routes deliberately kept off the @Rpc CollectionService contract, on REST.",
)
internal class CollectionInboxApi(
    private val clientFactory: ApiClientFactory,
) : CollectionInboxApiContract {
    override suspend fun listInbox(libraryId: String): AppResult<List<String>> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .get("/api/v1/admin/collections/inbox") {
                    parameter("libraryId", libraryId)
                }.body<List<String>>()
        }

    override suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit> =
        suspendRunCatching {
            // Success is the absence of a thrown ResponseException (expectSuccess = true
            // raises on non-2xx); the route replies 204 No Content with no body to decode.
            clientFactory
                .getClient()
                .post("/api/v1/admin/collections/inbox/release") {
                    parameter("libraryId", libraryId)
                    setBody(assignments)
                }.status
        }.map { }
}
