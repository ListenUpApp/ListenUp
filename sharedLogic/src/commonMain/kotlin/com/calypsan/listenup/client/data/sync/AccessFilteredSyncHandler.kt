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
     *
     * Whole-domain sweep — correct for a coarse re-derive ([catchUpTransient] returned the caller's
     * entire accessible set). For a scoped delta, use [pruneWithin] instead, which never touches a
     * row outside the scope.
     */
    suspend fun pruneTo(
        accessibleIds: Set<String>,
        now: Long,
    )

    /**
     * Scoped prune: soft-delete every live local row that is BOTH in [candidateIds] AND absent from
     * [accessibleIds] — the removal half of the scoped `AccessChanged` delta.
     *
     * Restricting the doomed set to [candidateIds] IS the substrate protection: a live row OUTSIDE
     * the scope — e.g. a public `ALL_BOOKS` book the client mirrors but that a targeted delta never
     * named — is never a candidate, so a scoped delta can never tombstone it. This is the structural
     * difference from [pruneTo], which sweeps every live row not in [accessibleIds] and so is only
     * sound when [accessibleIds] is the caller's WHOLE accessible set, not a targeted subset.
     *
     * [now] is the tombstone timestamp; already-tombstoned rows stay tombstoned, accessible rows are
     * left untouched (no UI flicker).
     */
    suspend fun pruneWithin(
        candidateIds: Set<String>,
        accessibleIds: Set<String>,
        now: Long,
    )
}
