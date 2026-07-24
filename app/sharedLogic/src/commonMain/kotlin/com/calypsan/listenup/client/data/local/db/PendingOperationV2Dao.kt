package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.calypsan.listenup.client.data.sync.MAX_RETRYABLE_ATTEMPTS
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the v2 pending-operation queue used by the client sync engine. See
 * [PendingOperationV2Entity] for the row schema and ordering contract.
 */
@Dao
internal interface PendingOperationV2Dao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(op: PendingOperationV2Entity)

    @Query("SELECT * FROM pending_operation WHERE clientOpId = :clientOpId")
    suspend fun get(clientOpId: String): PendingOperationV2Entity?

    @Query("DELETE FROM pending_operation WHERE clientOpId = :clientOpId")
    suspend fun delete(clientOpId: String)

    @Update
    suspend fun update(op: PendingOperationV2Entity)

    /**
     * Earliest-enqueued op per (domainName, entityId) group with retry budget remaining.
     * Per-entity FIFO: if an op for entity `E` is in flight, the next op for `E` waits;
     * ops on other entities can drain in parallel.
     *
     * Same-`enqueuedAt` ties (same-millisecond enqueue) are broken deterministically by
     * `clientOpId ASC` via the `ROW_NUMBER()` window function, rather than relying on
     * SQLite's undocumented bare-column `GROUP BY` tie-break. In practice ties are also
     * prevented at the source — [PendingOperationV2Dao] callers (see `PendingOperationQueue.enqueue`)
     * bump `enqueuedAt` so a second op for an entity that already has one queued is never
     * enqueued at the same instant — but this ordering holds even if that invariant is
     * ever violated.
     *
     * [ownerUserId], when non-null, is the structural guard against draining an orphaned op
     * under the wrong user's session: an op enqueued by a just-signed-out user's in-flight
     * write (a race the enqueue-time owner stamp alone can't close — see `OfflineEditor.edit`)
     * simply never matches a different active owner, no matter when it lands in the table.
     * `null` means unscoped (pre-existing behavior; every production caller passes the active
     * owner via [PendingOperationQueue]).
     */
    @Query(
        """
        SELECT clientOpId, domainName, entityId, opType, payload, enqueuedAt, lastAttemptAt, failureCount, lastError, ownerUserId
          FROM (
              SELECT *, ROW_NUMBER() OVER (
                  PARTITION BY domainName, entityId
                  ORDER BY enqueuedAt ASC, clientOpId ASC
              ) AS rn
                FROM pending_operation
               WHERE failureCount <= :maxAttempts
                 AND (:ownerUserId IS NULL OR ownerUserId = :ownerUserId)
          )
         WHERE rn = 1
         ORDER BY enqueuedAt ASC, clientOpId ASC
        """,
    )
    suspend fun nextDispatchable(
        ownerUserId: String? = null,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    ): List<PendingOperationV2Entity>

    /**
     * Count of ops still within retry budget — i.e. rows a future drain wave could dispatch.
     * See [nextDispatchable] for the [ownerUserId] scoping contract.
     */
    @Query(
        """
        SELECT COUNT(*) FROM pending_operation
         WHERE failureCount <= :maxAttempts
           AND (:ownerUserId IS NULL OR ownerUserId = :ownerUserId)
        """,
    )
    suspend fun countDispatchable(
        ownerUserId: String? = null,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    ): Int

    /**
     * Latest `enqueuedAt` among all ops (dispatchable or dead-lettered) for (domainName, entityId),
     * or null if none are queued. Backs the enqueue-time monotonic bump in `PendingOperationQueue.enqueue`
     * that keeps same-entity ops from ever tying on `enqueuedAt`.
     */
    @Query("SELECT MAX(enqueuedAt) FROM pending_operation WHERE domainName = :domainName AND entityId = :entityId")
    suspend fun maxEnqueuedAtFor(
        domainName: String,
        entityId: String,
    ): Long?

    /**
     * True when a still-dispatchable (within retry budget) op exists for (domainName, entityId).
     * Backs the anti-flicker in-flight shield: an inbound echo/catch-up for an entity with a queued
     * local edit is skipped so the optimistic state survives until the edit's own echo lands.
     *
     * Dead letters (`failureCount > maxAttempts`) are EXCLUDED, so a terminally-failed op stops
     * counting as in-flight — the shield lifts and the entity converges to server truth. See
     * [nextDispatchable] for the [ownerUserId] scoping contract.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM pending_operation
             WHERE domainName = :domainName
               AND entityId = :entityId
               AND failureCount <= :maxAttempts
               AND (:ownerUserId IS NULL OR ownerUserId = :ownerUserId)
        )
        """,
    )
    suspend fun hasQueuedOp(
        domainName: String,
        entityId: String,
        ownerUserId: String? = null,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    ): Boolean

    /**
     * Live queue depth: ops still eligible for dispatch. Dead letters are counted separately.
     * See [nextDispatchable] for the [ownerUserId] scoping contract.
     */
    @Query(
        """
        SELECT COUNT(*) FROM pending_operation
         WHERE failureCount <= :maxAttempts
           AND (:ownerUserId IS NULL OR ownerUserId = :ownerUserId)
        """,
    )
    fun observeQueueDepth(
        ownerUserId: String? = null,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    ): Flow<Int>

    /**
     * Terminal ops that exhausted their retry budget — the dead-letter count.
     * See [nextDispatchable] for the [ownerUserId] scoping contract.
     */
    @Query(
        """
        SELECT COUNT(*) FROM pending_operation
         WHERE failureCount > :maxAttempts
           AND (:ownerUserId IS NULL OR ownerUserId = :ownerUserId)
        """,
    )
    fun observeDeadLetterCount(
        ownerUserId: String? = null,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    ): Flow<Int>

    /**
     * One-shot dead-letter count — the same predicate as [observeDeadLetterCount] read directly.
     * A plain suspend `SELECT` sees a committed write immediately, without waiting on Room's
     * [androidx.room.InvalidationTracker] to notify the reactive Flow; tests use it to confirm a
     * write landed deterministically, decoupled from invalidation-propagation latency.
     */
    @Query("SELECT COUNT(*) FROM pending_operation WHERE failureCount > :maxAttempts")
    suspend fun countDeadLetters(maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS): Int

    /**
     * Live snapshots of ops still within retry budget, oldest first. Backs the
     * sync indicator's visible pending list. See [nextDispatchable] for the
     * [ownerUserId] scoping contract.
     */
    @Query(
        """
        SELECT * FROM pending_operation
         WHERE failureCount <= :maxAttempts
           AND (:ownerUserId IS NULL OR ownerUserId = :ownerUserId)
         ORDER BY enqueuedAt ASC
        """,
    )
    fun observePending(
        ownerUserId: String? = null,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    ): Flow<List<PendingOperationV2Entity>>

    /**
     * Live snapshots of terminally failed ops (past [maxAttempts]), oldest first.
     * Backs the sync indicator's failed-operations list. See [nextDispatchable] for
     * the [ownerUserId] scoping contract.
     */
    @Query(
        """
        SELECT * FROM pending_operation
         WHERE failureCount > :maxAttempts
           AND (:ownerUserId IS NULL OR ownerUserId = :ownerUserId)
         ORDER BY enqueuedAt ASC
        """,
    )
    fun observeFailed(
        ownerUserId: String? = null,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    ): Flow<List<PendingOperationV2Entity>>

    /** Re-arm an op for dispatch: zero its retry budget and clear the stored error. */
    @Query("UPDATE pending_operation SET failureCount = 0, lastError = NULL WHERE clientOpId = :clientOpId")
    suspend fun resetFailureCount(clientOpId: String)

    /**
     * Delete still-queued (within retry budget) ops for one (domain, entity, opType) slot.
     * Backs replace-on-enqueue coalescing; terminally-failed rows (failureCount > [maxAttempts])
     * are preserved — diagnostic state for the failed-operation surface, not superseded work.
     */
    @Query(
        """
        DELETE FROM pending_operation
         WHERE domainName = :domainName
           AND entityId = :entityId
           AND opType = :opType
           AND failureCount <= :maxAttempts
        """,
    )
    suspend fun deleteQueuedOps(
        domainName: String,
        entityId: String,
        opType: String,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    )

    @Query("DELETE FROM pending_operation WHERE ownerUserId != :keepUserId")
    suspend fun deleteAllExcept(keepUserId: String)

    @Query("DELETE FROM pending_operation")
    suspend fun deleteAll()

    /**
     * Deletes dead letters whose last activity predates [cutoffMillis]. `lastAttemptAt`
     * is the terminal op's final attempt; ops that never dispatched fall back to
     * [PendingOperationV2Entity.enqueuedAt].
     */
    @Query(
        "DELETE FROM pending_operation " +
            "WHERE failureCount > :maxAttempts AND COALESCE(lastAttemptAt, enqueuedAt) < :cutoffMillis",
    )
    suspend fun gcDeadLetters(
        cutoffMillis: Long,
        maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS,
    )
}
