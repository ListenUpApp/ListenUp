package com.calypsan.listenup.client.data.sync

/**
 * Snapshot of a pending operation at dispatch time. The wire-bound [payload]
 * is opaque JSON shaped by `(domainName, opType)`; the queue does not parse it.
 */
internal data class PendingOperation(
    /**
     * Local correlation id only — never sent to the server over the RPC write path and never
     * echoes back on a firehose `SyncEvent`. The anti-flicker shield keys on entity identity
     * `(domainName, entityId)` instead; see
     * [com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery].
     */
    val clientOpId: String,
    val domainName: String,
    val entityId: String,
    val opType: String,
    val payload: String,
    val enqueuedAt: Long,
    val failureCount: Int,
    val ownerUserId: String,
    /** Stable [com.calypsan.listenup.api.error.AppError.code] of the last failure, if any. */
    val lastError: String? = null,
)
