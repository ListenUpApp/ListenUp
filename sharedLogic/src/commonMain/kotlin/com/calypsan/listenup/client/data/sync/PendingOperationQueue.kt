package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private val logger = KotlinLogging.logger {}

internal const val MAX_RETRYABLE_ATTEMPTS = 5

/**
 * What a single [PendingOperationQueue.drain] wave produced. The engine reads
 * [retryableFailures] to decide whether to reschedule another drain on a
 * backoff timer — `drain()` is one-wave-only and never loops internally, so
 * recurring retry is the engine's responsibility.
 *
 * @property sent count of ops successfully ack'd by the server in this wave
 * @property retryableFailures count of ops that failed retryably (still in queue,
 *   `failureCount` incremented, will be picked up by a future drain)
 * @property terminalFailures count of ops that failed non-retryably (flagged
 *   past [MAX_RETRYABLE_ATTEMPTS] and will not be retried)
 * @property remainingDispatchable count of ops still within retry budget after this
 *   wave — includes ops held back by per-entity FIFO (a second op queued behind
 *   one just sent) AND this wave's own retryable failures (still eligible, just
 *   not yet retried). The engine uses this to decide whether looping `drain()`
 *   again immediately would make further progress.
 */
internal data class DrainOutcome(
    val sent: Int,
    val retryableFailures: Int,
    val terminalFailures: Int,
    val remainingDispatchable: Int,
) {
    /** True when this wave produced at least one retryable failure that the engine should reschedule. */
    val hasRetryableFailures: Boolean get() = retryableFailures > 0
}

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
 * The engine schedules drain on three signals: [observeEnqueueSignal] (a new
 * op landed), connection state transitioning to [ConnectionState.Connected],
 * and a backoff timer it owns after a drain wave reports retryable failures
 * via [DrainOutcome].
 */
internal class PendingOperationQueue(
    private val dao: PendingOperationV2Dao,
    private val sender: PendingOperationSender,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    // The engine subscribes to this counter to schedule a drain whenever a new
    // op lands. A monotonic-counter StateFlow rather than a unit SharedFlow
    // because StateFlow's "new subscriber sees current value" semantics avoid
    // the subscriber-race window that loses signals on a fresh subscription —
    // the engine drops the initial value and reacts only to subsequent
    // increments, which the engine treats as "an op was enqueued since you
    // started listening."
    private val enqueueCounter = MutableStateFlow(0L)

    /**
     * Monotonically increasing counter that ticks each time an op is enqueued.
     * The engine collects increments to schedule a drain so local-first writes
     * propagate without waiting for the next connection event. The initial
     * value is unspecified; collectors should react to *changes* from whatever
     * they observe on first attach.
     */
    fun observeEnqueueSignal(): StateFlow<Long> = enqueueCounter.asStateFlow()

    /**
     * Enqueue a new op. Returns its generated `clientOpId`.
     *
     * @param coalesce When true, deletes any still-queued (within retry budget) op for the same
     *   (domainName, entityId, opType) slot before inserting — so rapid successive writes for one
     *   entity collapse to the latest snapshot instead of piling up. Valid only for domains where
     *   the payload is a last-write-wins snapshot of current state (e.g. playback positions);
     *   event/entity-PATCH domains keep the default `false` so every op is replayed. Terminally
     *   failed rows are never coalesced away — see [PendingOperationV2Dao.deleteQueuedOps]. The
     *   delete-then-insert is non-atomic: the one coalescing caller today serializes per entity on
     *   a Mutex, and racing a concurrent drain is benign (Room `@Update`/`@Delete` on an
     *   already-removed row is a silent no-op).
     */
    suspend fun enqueue(
        domainName: String,
        entityId: String,
        opType: String,
        payload: String,
        ownerUserId: String,
        coalesce: Boolean = false,
    ): String {
        if (coalesce) {
            dao.deleteQueuedOps(domainName, entityId, opType)
        }
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
        enqueueCounter.update { it + 1 }
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
     * loop. Returns a [DrainOutcome] so the engine can decide whether a retry
     * backoff is warranted.
     *
     * A [PendingOperationSender] that throws instead of returning
     * [AppResult.Failure] is a bug in that sender, not a reason to abort the
     * wave — the op is flagged terminally failed (past [MAX_RETRYABLE_ATTEMPTS])
     * and the loop continues to the next op.
     */
    suspend fun drain(): DrainOutcome {
        val ops = dao.nextDispatchable()
        var sent = 0
        var retryableFailures = 0
        var terminalFailures = 0
        for (entity in ops) {
            val op = entity.toDomain()
            val result =
                try {
                    sender.send(op)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Sender threw for op ${op.clientOpId} (${op.domainName}); flagging terminal" }
                    dao.update(
                        entity.copy(
                            failureCount = MAX_RETRYABLE_ATTEMPTS + 1,
                            lastAttemptAt = nowMillis(),
                            lastError = e::class.simpleName ?: "SenderException",
                        ),
                    )
                    terminalFailures++
                    continue
                }
            when (result) {
                is AppResult.Success -> {
                    dao.delete(op.clientOpId)
                    sent++
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
                    if (retryable) retryableFailures++ else terminalFailures++
                    logger.warn {
                        "Pending op ${op.clientOpId} failed: ${result.error.code} " +
                            "(retryable=$retryable, count=$newCount)"
                    }
                }
            }
        }
        return DrainOutcome(
            sent = sent,
            retryableFailures = retryableFailures,
            terminalFailures = terminalFailures,
            remainingDispatchable = dao.countDispatchable(),
        )
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
