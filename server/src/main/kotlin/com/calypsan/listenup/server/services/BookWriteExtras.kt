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
    val inboxCollectionId: String? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BookWriteExtras>
}
