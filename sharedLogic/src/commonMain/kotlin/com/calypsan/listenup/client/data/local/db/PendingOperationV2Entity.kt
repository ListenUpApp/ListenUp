package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local-first pending write awaiting server replay. Each row keys on a
 * client-generated UUID ([clientOpId]) which propagates to the server via the
 * write API and echoes back on the SSE [com.calypsan.listenup.api.sync.SyncEvent]
 * so the engine can match echoes to ops.
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
)
