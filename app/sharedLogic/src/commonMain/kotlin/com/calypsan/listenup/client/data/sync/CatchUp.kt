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
     * live firehose/cursor path continues independently; this is a one-shot re-derivation, not a
     * cursor rewind.
     */
    suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>>

    /**
     * Targeted, access-filtered fetch of just the rows named by [fetch] — the read half of the
     * scoped `AccessChanged` delta, [catchUpTransient] minus the from-0 paging. Each returned row is
     * applied through [SyncDomainHandler.onCatchUpItem], so it inherits the same revision guard and
     * in-flight shield as paged catch-up. Returns the non-tombstone ids that came
     * back — for [TargetedFetch.ByIds] that is the still-accessible subset of the requested ids (the
     * requested-but-not-returned remainder is what the caller prunes); the persisted
     * [SyncCursorStore] is deliberately untouched, exactly like [catchUpTransient].
     *
     * The default returns the empty set: only the production [SyncCatchUpClient] performs the real
     * HTTP fetch; a test fake that does not drive the delta path can leave this default.
     */
    suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> = AppResult.Success(emptySet())

    suspend fun domains(): AppResult<List<String>>
}

/**
 * A targeted, cursor-agnostic fetch of a subset of an access-gated domain — names WHICH rows to
 * pull for the scoped `AccessChanged` delta. The server access-filters the result regardless, so a
 * returned row is one the caller may still see.
 */
internal sealed interface TargetedFetch {
    /** Match rows by their own wire id — books and collections. */
    data class ByIds(
        val ids: List<String>,
    ) : TargetedFetch

    /** Match rows by their `collection_id` — collection_books, to pull a set of collections' memberships. */
    data class ByCollectionIds(
        val collectionIds: List<String>,
    ) : TargetedFetch

    /**
     * Match rows by their `book_id` — activities, to pull the activity rows gating on a set of books.
     *
     * Version skew: a new client sending `?bookIds=` to an OLD server (no `book_id` branch) hits the
     * server's `else`/`400` path, which surfaces as an [AppResult.Failure] → one bounded delta requeue
     * → the digest backstop. Same eventual convergence as before this fetch existed; acceptable.
     */
    data class ByBookIds(
        val bookIds: List<String>,
    ) : TargetedFetch
}
