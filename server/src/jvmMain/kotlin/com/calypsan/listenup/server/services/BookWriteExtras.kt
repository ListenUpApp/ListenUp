package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.cover.StoredCoverInfo
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-write extras the scan path injects into [BookRepository.writePayload] via the coroutine
 * context — mirrors `FirehoseSuppressed`. Replaces the shared-mutable `pendingManagedCovers`/
 * `pendingInboxIds` maps: scoped to the call, no cross-book race, no manual lifecycle.
 */
class BookWriteExtras(
    val managedCover: StoredCoverInfo? = null,
    val systemCollectionId: String? = null,
    /**
     * Edit-path override for the book's `createdAt` (the "added date"). Non-null
     * only when an explicit metadata edit re-stamps the added date — [writePayload]'s
     * UPDATE branch writes it through. The scanner never sets this, so a rescan's
     * placeholder `createdAt` keeps being ignored on update.
     */
    val createdAtOverride: Long? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BookWriteExtras>
}
