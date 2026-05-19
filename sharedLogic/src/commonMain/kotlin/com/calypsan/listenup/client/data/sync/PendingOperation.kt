package com.calypsan.listenup.client.data.sync

/**
 * Snapshot of a pending operation at dispatch time. The wire-bound [payload]
 * is opaque JSON shaped by `(domainName, opType)`; the queue does not parse it.
 */
data class PendingOperation(
    val clientOpId: String,
    val domainName: String,
    val entityId: String,
    val opType: String,
    val payload: String,
    val enqueuedAt: Long,
    val failureCount: Int,
    val ownerUserId: String,
)
