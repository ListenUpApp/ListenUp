package com.calypsan.listenup.client.data.local.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Local-first pending write awaiting server replay. Each row keys on a
 * client-generated UUID ([clientOpId]) — a local correlation id only: it is never sent to the
 * server over the RPC write path and never echoes back on a firehose
 * [com.calypsan.listenup.api.sync.SyncEvent]. The anti-flicker shield that stops an inbound
 * echo/catch-up snapshot from reverting a still-in-flight local edit keys on entity identity
 * `(domainName, entityId)` instead — see
 * [com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery].
 *
 * Per-entity FIFO ordering: ops are drained in order of [enqueuedAt] within
 * `(domainName, entityId)` groups; different entities drain in parallel.
 */
@Entity(
    tableName = "pending_operation",
    indices = [
        Index(value = ["domainName", "entityId", "enqueuedAt"]),
        Index(value = ["ownerUserId"]),
    ],
)
internal data class PendingOperationV2Entity(
    @PrimaryKey val clientOpId: String,
    val domainName: String,
    val entityId: String,
    /** [com.calypsan.listenup.client.data.sync.domains.OpKind.wire] value ("update", "upsert"; "create"/"delete" reserved). */
    val opType: String,
    /** JSON-serialized payload, shape per (domainName, opType). */
    val payload: String,
    val enqueuedAt: Long,
    val lastAttemptAt: Long?,
    val failureCount: Int,
    val lastError: String?,
    /** User id this op belongs to. On sign-in mismatch, the queue is cleared. */
    val ownerUserId: String,
    /**
     * True once a send may have reached the server without a verdict that it didn't land: a lost response
     * (`OutcomeUnknown`), a server-answered retryable failure, or a sender that threw mid-send. Sticky —
     * a later pre-send failure (unreachable, timeout, auth) never clears it. Distinct from [lastAttemptAt],
     * which a pre-send failure also stamps although the op never left the device. Read by
     * `PendingOperationQueue.cancelUnsent`: an op that may have landed can't be withdrawn as unsent.
     */
    @ColumnInfo(defaultValue = "0")
    val mayHaveLanded: Boolean = false,
)
