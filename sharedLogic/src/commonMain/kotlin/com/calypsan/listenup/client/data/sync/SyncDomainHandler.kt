package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import kotlinx.serialization.KSerializer

/**
 * Per-domain extension seam for the client sync engine.
 *
 * Implementations are Koin singletons with `createdAtStart = true`; each
 * self-registers in its `init` block via `ClientSyncDomainRegistry.register(this)`.
 * The engine never references concrete handlers — only this interface.
 *
 * Both methods return [AppResult] so domain failures surface as typed errors that
 * advance the engine's error metrics. Implementations must NOT throw — Konsist
 * pins this in [com.calypsan.listenup.konsist.SyncDomainHandlersUseAppResultRule].
 */
internal interface SyncDomainHandler<T : Any> {
    /** Domain name as it appears on the wire (e.g. `"tags"`, `"books"`). */
    val domainName: String

    /** kotlinx.serialization serializer for the domain's wire DTO [T]. */
    val payloadSerializer: KSerializer<T>

    /**
     * The stable sync id for [item] — the same id used on the SSE envelope and as the local
     * row's identity. For most domains this is the payload's `id` field; composite-key domains
     * (e.g. `collection_books`) synthesise it from their parts (`"$collectionId:$bookId"`).
     *
     * Used by [CatchUp.catchUpTransient] to collect the accessible id set during the
     * `AccessChanged` reconcile, so it must line up with [AccessFilteredSyncHandler.localLiveIds]
     * for access-gated handlers.
     */
    fun syncId(item: T): String

    /**
     * Apply an SSE-driven event.
     *
     * Anti-flicker is structural, not per-call: the engine's apply choke point shields an inbound
     * snapshot for an entity whose local edit is still in flight (a queued outbox op), so a stale
     * echo can never revert the optimistic state — the edit's own echo applies once it drains.
     * Implementations therefore apply the canonical server state unconditionally here.
     *
     * For [SyncEvent.Deleted] events the payload is absent; apply the tombstone
     * by id alone. For [SyncEvent.Created] / [SyncEvent.Updated] the payload is
     * the canonical server state.
     */
    suspend fun onEvent(event: SyncEvent<T>): AppResult<Unit>

    /**
     * Apply an item from REST catch-up paging. [isTombstone] is `true` when
     * `item` has `deletedAt != null`, signalling a soft-delete the client must
     * apply by id (the payload is canonical state, not deletion metadata).
     */
    suspend fun onCatchUpItem(
        item: T,
        isTombstone: Boolean,
    ): AppResult<Unit>

    /**
     * The domain's local `(id, revision)` rows with `revision <= maxRevision`, EXCLUDING
     * soft-deleted rows — the exact LIVE set the server's (tombstone-excluding) digest covers.
     * Used by the reconciler to fingerprint the domain and detect per-domain drift; the
     * tombstone exclusion is what lets a member who tombstoned a row locally converge (F1).
     *
     * Returns `null` for a domain that cannot be fingerprinted client-side (no local
     * `revision` column) — the reconciler skips such domains rather than re-pulling them
     * spuriously on every connect.
     */
    suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>>?
}
