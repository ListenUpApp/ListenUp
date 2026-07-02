package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val MAX_RETRYABLE_ATTEMPTS = 5

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
     */
    @Query(
        """
        SELECT * FROM pending_operation
         WHERE failureCount <= :maxAttempts
         GROUP BY domainName, entityId
        HAVING enqueuedAt = MIN(enqueuedAt)
         ORDER BY enqueuedAt ASC
        """,
    )
    suspend fun nextDispatchable(maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS): List<PendingOperationV2Entity>

    /** Count of ops still within retry budget — i.e. rows a future drain wave could dispatch. */
    @Query("SELECT COUNT(*) FROM pending_operation WHERE failureCount <= :maxAttempts")
    suspend fun countDispatchable(maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS): Int

    @Query("SELECT COUNT(*) FROM pending_operation")
    fun observeQueueDepth(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_operation WHERE failureCount > :maxAttempts")
    fun observeFailureCount(maxAttempts: Int = MAX_RETRYABLE_ATTEMPTS): Flow<Int>

    @Query("DELETE FROM pending_operation WHERE ownerUserId != :keepUserId")
    suspend fun deleteAllExcept(keepUserId: String)

    @Query("DELETE FROM pending_operation")
    suspend fun deleteAll()
}
