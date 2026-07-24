package com.calypsan.listenup.client.data.sync.domains

/**
 * The kind of a queued outbox operation. [wire] is the string persisted in the
 * queue's `opType` column and is FROZEN — rows already on devices carry
 * `"update"`/`"upsert"` and must keep draining across app updates.
 */
internal enum class OpKind(
    val wire: String,
) {
    Create("create"),
    Update("update"),
    Delete("delete"),
    Upsert("upsert"),
}

/**
 * The domain's client-write posture. [Outbox] points at the [OutboxChannel] the
 * sender map, [com.calypsan.listenup.client.data.sync.OfflineEditor], and the
 * queue's op validation all derive from — the completeness spec pins each
 * declared tier to its channel, so a declared tier and the runtime path cannot
 * drift.
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
    ) : WriteTier
}
