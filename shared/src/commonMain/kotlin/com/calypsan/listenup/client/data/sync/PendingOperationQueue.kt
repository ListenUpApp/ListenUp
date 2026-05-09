package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

private val logger = KotlinLogging.logger {}

internal const val MAX_RETRYABLE_ATTEMPTS = 5

/**
 * Per-entity FIFO queue of local-first writes awaiting server replay.
 *
 * - [enqueue] records the op with a generated `clientOpId` and returns the id
 *   so the caller can correlate later echoes.
 * - [drain] dispatches one op per (domain, entityId) group via [PendingOperationSender]
 *   sequentially within a single call; cross-entity parallelism emerges across
 *   concurrent drain() schedulings, not within one. Per-entity FIFO comes from
 *   the SQL filter, not Mutex coordination.
 * - [containsAndAck] is the dispatcher's echo-matching primitive: present →
 *   true and remove the op (ack); absent → false (event is a remote write).
 * - On retryable failure, `failureCount` increments; on non-retryable failure
 *   it leaps past [MAX_RETRYABLE_ATTEMPTS] so it never retries.
 *
 * Drain is invoked by the engine on a coroutine — *not* a self-running loop.
 * The engine schedules drain on enqueue, on connection-up, and on retry
 * backoff timers.
 */
@OptIn(ExperimentalUuidApi::class)
class PendingOperationQueue(
    private val dao: PendingOperationV2Dao,
    private val sender: PendingOperationSender,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Enqueue a new op. Returns its generated `clientOpId`. */
    suspend fun enqueue(
        domainName: String,
        entityId: String,
        opType: String,
        payload: String,
        ownerUserId: String,
    ): String {
        val opId = Uuid.random().toString()
        dao.insert(
            PendingOperationV2Entity(
                clientOpId = opId,
                domainName = domainName,
                entityId = entityId,
                opType = opType,
                payload = payload,
                enqueuedAt = nowMillis(),
                lastAttemptAt = null,
                failureCount = 0,
                lastError = null,
                ownerUserId = ownerUserId,
            ),
        )
        return opId
    }

    /**
     * If [clientOpId] is in the queue, delete it and return true. Used by the
     * dispatcher to ack echoes.
     *
     * Get + delete is intentionally non-atomic — production usage is
     * single-coroutine (the SSE dispatcher), and adding a Mutex would mask
     * the design constraint without buying anything under that invariant.
     */
    suspend fun containsAndAck(clientOpId: String): Boolean {
        val existing = dao.get(clientOpId) ?: return false
        dao.delete(existing.clientOpId)
        return true
    }

    /**
     * Dispatch the earliest-enqueued op per (domain, entityId) group currently
     * available; each result mutates the row in place (delete on success,
     * increment `failureCount` on failure).
     *
     * Within a single drain() call, dispatch is sequential — one op at a time.
     * Per-entity FIFO is enforced by the SQL filter (one earliest op per
     * group), not by intra-call concurrency. Cross-entity parallelism emerges
     * across concurrent drain() schedulings: rows for different entities are
     * independent and the SQL filter never picks the same row twice, so
     * concurrent drain() calls do not contend on the same entity.
     *
     * One call = one wave. Caller schedules subsequent waves; drain() does not
     * loop.
     */
    suspend fun drain() {
        val ops = dao.nextDispatchable()
        for (entity in ops) {
            val op = entity.toDomain()
            val result = sender.send(op)
            when (result) {
                is AppResult.Success -> {
                    dao.delete(op.clientOpId)
                }

                is AppResult.Failure -> {
                    val retryable = result.error.isRetryable
                    val newCount =
                        if (retryable) {
                            entity.failureCount + 1
                        } else {
                            // Leap past MAX so the SQL filter never picks it up again.
                            MAX_RETRYABLE_ATTEMPTS + 1
                        }
                    dao.update(
                        entity.copy(
                            failureCount = newCount,
                            lastAttemptAt = nowMillis(),
                            lastError = result.error.code,
                        ),
                    )
                    logger.warn {
                        "Pending op ${op.clientOpId} failed: ${result.error.code} " +
                            "(retryable=$retryable, count=$newCount)"
                    }
                }
            }
        }
    }

    /** Live count of all queued ops. Engine forwards this to `SyncEngineState`. */
    fun observeQueueDepth(): Flow<Int> = dao.observeQueueDepth()

    /** Live count of ops past [MAX_RETRYABLE_ATTEMPTS]. Engine forwards to `SyncEngineState`. */
    fun observeFailureCount(): Flow<Int> = dao.observeFailureCount()

    /**
     * Wipe ops not owned by [currentUserId]. Called by `SyncEngine.start` when
     * the signed-in user differs from the one whose ops are queued. Same-user
     * sign-out is a pause, not a clear.
     */
    suspend fun clearForUserChange(currentUserId: String) {
        dao.deleteAllExcept(currentUserId)
    }

    private fun PendingOperationV2Entity.toDomain() =
        PendingOperation(
            clientOpId = clientOpId,
            domainName = domainName,
            entityId = entityId,
            opType = opType,
            payload = payload,
            enqueuedAt = enqueuedAt,
            failureCount = failureCount,
            ownerUserId = ownerUserId,
        )
}
