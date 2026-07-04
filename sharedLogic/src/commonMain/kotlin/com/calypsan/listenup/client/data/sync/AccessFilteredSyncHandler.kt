package com.calypsan.listenup.client.data.sync

/**
 * Capability marker for sync handlers whose domain is access-gated on the server.
 *
 * The server's `pullSince` for these domains is filtered to the caller's accessible set
 * (see Collections 1b), so a transient catch-up from cursor 0 returns exactly what the
 * caller may currently see. On a `SyncControl.AccessChanged` signal the engine re-derives
 * that set via [com.calypsan.listenup.client.data.sync.CatchUp.catchUpTransient] and then
 * calls [pruneTo] to evict every locally-live row that is no longer accessible — closing
 * the gap where a revoked share would otherwise leave inaccessible rows in Room.
 *
 * Implemented only by the five access-gated handlers (`books`, `activities`, and the three
 * collection domains). The engine discovers them via
 * [com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry.accessFilteredHandlers].
 */
internal interface AccessFilteredSyncHandler {
    /**
     * The ids of every live (non-tombstoned) local row in this domain. For composite-key
     * domains (e.g. `collection_books`) the id is the synthetic `"$collectionId:$bookId"`
     * form the server uses on the wire — so it lines up with the set returned by
     * [com.calypsan.listenup.client.data.sync.CatchUp.catchUpTransient].
     */
    suspend fun localLiveIds(): Set<String>

    /**
     * Soft-delete (tombstone) every live local row whose id is NOT in [accessibleIds].
     * [now] is the tombstone timestamp. Rows already accessible are left untouched so the
     * UI never flickers; rows already tombstoned stay tombstoned.
     */
    suspend fun pruneTo(
        accessibleIds: Set<String>,
        now: Long,
    )
}
