package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult

/**
 * The narrow inbox-add capability [BookPersister] needs to land a newly-scanned
 * book in its library's inbox.
 *
 * Exists to break the layering direction: the scan path (a low-level persistence
 * concern) must not depend on the full [com.calypsan.listenup.api.CollectionService]
 * surface, and the substrate [BookRepository] must not depend on a service at
 * all. The sole production implementation is
 * [com.calypsan.listenup.server.api.CollectionServiceImpl], which already owns
 * inbox resolution and book linking. A hand-written fake stands in for the
 * persister's orchestration tests.
 */
interface InboxIngest {
    /**
     * Adds [bookId] to [libraryId]'s inbox, resolving (or creating) the inbox
     * first. Idempotent at the junction level; intended for newly-scanned books
     * only — the caller gates on new-vs-existing.
     */
    suspend fun addToInbox(
        bookId: String,
        libraryId: String,
    ): AppResult<Unit>
}
