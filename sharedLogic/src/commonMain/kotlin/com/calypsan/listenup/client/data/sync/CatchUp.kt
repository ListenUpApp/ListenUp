package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult

/**
 * Engine-facing seam for REST catch-up. Allows [SyncEngine] to be tested with
 * fakes without dragging the full HTTP client. Implemented by [SyncCatchUpClient].
 */
internal interface CatchUp {
    suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit>

    /**
     * Re-pull [handler]'s domain from `since = 0`, applying every item and advancing the
     * persisted cursor. Used by the reconciler to repair a domain whose digest diverged.
     */
    suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit>

    suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit>

    /**
     * Page an access-filtered domain from cursor 0 WITHOUT touching [SyncCursorStore],
     * upserting each returned item via the handler and collecting the live (non-tombstone)
     * ids into the returned set.
     *
     * Used by the `AccessChanged` reconcile: the server's `pullSince` for access-gated domains
     * is filtered to the caller's accessible set, so a transient pass from 0 yields exactly the
     * ids the caller may currently see. The persisted cursor is deliberately untouched — the
     * live SSE/cursor path continues independently; this is a one-shot re-derivation, not a
     * cursor rewind.
     */
    suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>>

    suspend fun domains(): AppResult<List<String>>
}
