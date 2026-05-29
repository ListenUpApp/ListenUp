package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.CollectionService].
 * All routes live under `/api/v1/collections` and require JWT authentication.
 *
 * RPC is the first-class surface; these resources exist so the same operations
 * are reachable over plain REST for third-party integrations.
 */
@Resource("/api/v1/collections")
class CollectionResources {
    /**
     * REST mirror for the collection collection:
     * - `GET /api/v1/collections` →
     *   [com.calypsan.listenup.api.CollectionService.listCollections]
     * - `POST /api/v1/collections` (body: `CreateCollectionBody`) →
     *   [com.calypsan.listenup.api.CollectionService.createCollection]
     */
    @Resource("")
    class List(
        val parent: CollectionResources = CollectionResources(),
    )

    /**
     * REST mirror for per-collection operations:
     * - `GET /api/v1/collections/{id}` →
     *   [com.calypsan.listenup.api.CollectionService.getCollection]
     * - `PATCH /api/v1/collections/{id}` (body: new name) →
     *   [com.calypsan.listenup.api.CollectionService.renameCollection]
     * - `DELETE /api/v1/collections/{id}` →
     *   [com.calypsan.listenup.api.CollectionService.deleteCollection]
     */
    @Resource("{id}")
    class Detail(
        val parent: CollectionResources = CollectionResources(),
        /** Collection id string (UUIDv7 at the storage layer). */
        val id: String,
    ) {
        /**
         * REST mirror for per-book membership operations:
         * - `PUT /api/v1/collections/{id}/books/{bookId}` →
         *   [com.calypsan.listenup.api.CollectionService.addBookToCollection]
         * - `DELETE /api/v1/collections/{id}/books/{bookId}` →
         *   [com.calypsan.listenup.api.CollectionService.removeBookFromCollection]
         */
        @Resource("books/{bookId}")
        class Book(
            val parent: Detail,
            /** Book id string of the book to add to or remove from the collection. */
            val bookId: String,
        )
    }

    /**
     * REST mirror of [com.calypsan.listenup.api.CollectionService.listCollectionBooks] —
     * `GET /api/v1/collections/{id}/books?limit=N` returns up to [limit] book IDs
     * that are members of the collection. [limit] is clamped server-side.
     */
    @Resource("{id}/books")
    class Books(
        val parent: CollectionResources = CollectionResources(),
        /** Collection id string. */
        val id: String,
        /** Maximum number of book IDs to return; clamped server-side. Default 500. */
        val limit: Int = 500,
    )

    /**
     * REST mirror for the share collection:
     * - `GET /api/v1/collections/{id}/shares` →
     *   [com.calypsan.listenup.api.CollectionService.listShares]
     * - `POST /api/v1/collections/{id}/shares` (body: `ShareCollectionBody`) →
     *   [com.calypsan.listenup.api.CollectionService.shareCollection]
     */
    @Resource("{id}/shares")
    class Shares(
        val parent: CollectionResources = CollectionResources(),
        /** Collection id string. */
        val id: String,
    )

    /**
     * REST mirror for per-share operations:
     * - `PATCH /api/v1/collections/{id}/shares/{userId}` (body: new permission) →
     *   [com.calypsan.listenup.api.CollectionService.updateShare]
     * - `DELETE /api/v1/collections/{id}/shares/{userId}` →
     *   [com.calypsan.listenup.api.CollectionService.revokeShare]
     */
    @Resource("{id}/shares/{userId}")
    class ShareDetail(
        val parent: CollectionResources = CollectionResources(),
        /** Collection id string. */
        val id: String,
        /** Id of the user whose share grant is being updated or revoked. */
        val userId: String,
    )
}
