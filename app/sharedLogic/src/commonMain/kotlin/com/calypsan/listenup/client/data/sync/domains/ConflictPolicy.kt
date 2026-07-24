package com.calypsan.listenup.client.data.sync.domains

/**
 * How a mirrored domain resolves an inbound server snapshot against local state.
 * Declared once per domain in its [MirroredDomain]; enforced by
 * [ComposedSyncDomainHandler]. Every variant exists because a real domain uses it —
 * none is speculative.
 *
 * Invariant generic (not `in`-variant) so `when` branches smart-cast without
 * unchecked casts; the stateless variants are classes for the same reason.
 */
internal sealed interface ConflictPolicy<T : Any> {
    /**
     * The server payload is canonical; apply unconditionally, subject to the
     * [revisionGuard] that skips inbound snapshots strictly older than the local
     * row. The guard lives on the policy because ServerWins is one of the two
     * policies that compare revisions. (tags, genres, …)
     */
    class ServerWins<T : Any>(
        val revisionGuard: RevisionGuard,
    ) : ConflictPolicy<T>

    /**
     * Append-only rows: the domain's [MirrorApply.upsert] is insert-if-absent, so
     * inbound can never overwrite an existing row. Declarative — the guard lives in
     * the apply. (listening_events)
     */
    class AppendOnly<T : Any> : ConflictPolicy<T>

    /**
     * Timestamp-guarded: skip the inbound snapshot when the local row is at least as
     * fresh. [incomingStamp] reads the payload's conflict timestamp; [existingStamp]
     * reads the local row's, inside the apply transaction. The `>=` comparison also
     * absorbs own-echoes, so echo state is not consulted. (playback_positions)
     */
    class NewerWins<T : Any>(
        val incomingStamp: (T) -> Long,
        val existingStamp: suspend (payload: T) -> Long?,
    ) : ConflictPolicy<T>
}
