package com.calypsan.listenup.client.data.sync.domains

/** The kind of a queued outbox operation. */
internal enum class OpKind { Create, Update, Delete, Upsert }

/**
 * The domain's client-write posture. [Outbox] points at the [OutboxChannel] the
 * sender map and queue validation will derive from once wired — the completeness
 * spec already pins each declared tier to its channel, so the declaration cannot
 * drift from the catalog. This is still declarative vocabulary; the runtime
 * derivation lands in a follow-up task.
 */
internal sealed interface WriteTier {
    /** No client-originated writes exist (server-materialized read models). */
    data object ServerOwned : WriteTier

    /**
     * Mutations are direct RPC and fail honestly offline — never silently queued.
     * (Prior product decisions: merges, deletes, admin, password, avatar.)
     */
    data object OnlineOnly : WriteTier

    /** Mutations write Room optimistically and queue durable ops on [channel]. */
    data class Outbox(
        val channel: OutboxChannel<*>,
    ) : WriteTier {
        val ops: Set<OpKind> get() = channel.ops
    }
}
