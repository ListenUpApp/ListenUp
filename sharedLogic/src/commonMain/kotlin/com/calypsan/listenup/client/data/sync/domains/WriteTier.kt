package com.calypsan.listenup.client.data.sync.domains

/** The kind of a queued outbox operation. */
internal enum class OpKind { Create, Update, Delete, Upsert }

/**
 * The domain's client-write posture. In Plan 1 this is DECLARATIVE ONLY — the
 * outbox `byDomain` sender map and [com.calypsan.listenup.client.data.sync.OfflineEditor]
 * are unchanged until Phase 4 derives them from [Outbox] entries. The declaration
 * still pays rent now: the catalog reads as the complete rulebook.
 */
internal sealed interface WriteTier {
    /** No client-originated writes exist (server-materialized read models). */
    data object ServerOwned : WriteTier

    /**
     * Mutations are direct RPC and fail honestly offline — never silently queued.
     * (Prior product decisions: merges, deletes, admin, password, avatar.)
     */
    data object OnlineOnly : WriteTier

    /** Mutations write Room optimistically and queue durable ops of the declared [ops]. */
    data class Outbox(
        val ops: Set<OpKind>,
    ) : WriteTier
}
