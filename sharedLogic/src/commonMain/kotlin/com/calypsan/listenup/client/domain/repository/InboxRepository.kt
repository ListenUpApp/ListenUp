@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult

/**
 * Repository contract for the admin collection inbox.
 *
 * The inbox is a system collection holding freshly-ingested books awaiting admin
 * triage. Both operations are admin-internal REST calls (Collections-1b admin routes),
 * not part of the `@Rpc CollectionService` surface. Reads are direct REST fetches of
 * the authoritative book-id set — the inbox is not mirrored into Room.
 *
 * Implementations live in the data layer.
 */
interface InboxRepository {
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
