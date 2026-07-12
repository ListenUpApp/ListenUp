package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private val logger = KotlinLogging.logger {}

internal const val MAX_RETRYABLE_ATTEMPTS = 5

/** Dead letters older than this are GC'd at the start of each drain wave. */
internal const val DEAD_LETTER_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * What a single [PendingOperationQueue.drain] wave produced. The engine reads
 * [retryableFailures] vs [parkedFailures] to decide whether to reschedule another
 * drain on a backoff timer — `drain()` is one-wave-only and never loops internally,
 * so recurring retry is the engine's responsibility.
 *
 * The distinction is the durability contract's core: budget (the [MAX_RETRYABLE_ATTEMPTS]
 * ceiling) is spent only when the SERVER ANSWERED. A failure where the server never
 * rendered a verdict (unreachable, or an idempotent lost-response) is *parked*, not
 * *burned* — it keeps its `failureCount` and re-sends the instant reachability returns.
 *
 * @property sent count of ops successfully ack'd by the server in this wave
 * @property retryableFailures count of ops the server ANSWERED with a retryable failure
 *   (5xx): `failureCount` incremented, dead-lettered after [MAX_RETRYABLE_ATTEMPTS]. The
 *   engine reschedules a backoff drain for these — the server is up but erroring transiently.
 * @property parkedFailures count of ops with NO server verdict (transport unreachable, or
 *   an idempotent-channel lost-response): `failureCount` UNCHANGED, still dispatchable. The
 *   engine does NOT busy-loop these on a timer — it parks them and lets the connection/reachability
 *   edge re-drive drain when the server is back.
 * @property terminalFailures count of ops that failed non-retryably (flagged
 *   past [MAX_RETRYABLE_ATTEMPTS] and will not be retried)
 * @property remainingDispatchable count of ops still within retry budget after this
 *   wave — includes ops held back by per-entity FIFO (a second op queued behind
 *   one just sent), this wave's own retryable failures, AND this wave's parked
 *   failures (all still eligible, just not yet re-sent). The engine uses this to
 *   decide whether looping `drain()` again immediately would make further progress.
 * @property sentEntities the `(domainName, entityId)` of every op that SENT (and was
 *   therefore deleted) this wave. The engine targeted-reconciles these after the wave so a
 *   server echo the entity-level anti-flicker shield dropped WHILE the op was in flight lands
 *   promptly — closing the "incomplete optimistic write stays stale until the next digest"
 *   gap (e.g. a new-by-name contributor with no local id). Empty on a wave that sent nothing.
 */
internal data class DrainOutcome(
    val sent: Int,
    val retryableFailures: Int,
    val parkedFailures: Int,
    val terminalFailures: Int,
    val remainingDispatchable: Int,
    val sentEntities: List<SentEntityRef> = emptyList(),
) {
    /** True when this wave produced a server-answered retryable failure the engine should reschedule on backoff. */
    val hasRetryableFailures: Boolean get() = retryableFailures > 0

    /** True when this wave parked at least one op (no server verdict) awaiting a reachability edge. */
    val hasParkedFailures: Boolean get() = parkedFailures > 0
}

/**
 * The identity of an op that SENT successfully in a drain wave — the `(domainName, entityId)`
 * pair the engine feeds to a targeted [CatchUp.fetchTransient] so the just-sent entity reconciles
 * to current server state (re-landing any echo the in-flight anti-flicker shield dropped). The
 * `entityId` is the sync-domain row id (a book edit's `bookId`), so it lines up with the domain
 * handler's `?ids=` fetch.
 */
internal data class SentEntityRef(
    val domainName: String,
    val entityId: String,
)

/**
 * How a failed send disposition affects the op's retry budget. The budget exists to eventually
 * quarantine a POISON op (a validation reject, a corrupt payload the server keeps refusing) — NOT
 * to punish an op for the network being down. So budget is spent only when the server answered.
 */
private enum class FailureDisposition {
    /**
     * No server verdict: the request never reached a responding server (unreachable), or was sent
     * on an idempotent channel with a lost response (safe to re-send). Park and retry when reachable —
     * do NOT burn budget.
     */
    Parked,

    /** The server ANSWERED with a retryable failure (5xx). Burn one attempt; quarantine after [MAX_RETRYABLE_ATTEMPTS]. */
    Burn,

    /** Non-retryable — the op can never succeed as-is. Dead-letter immediately. */
    Terminal,
}

/**
 * Classify a drain failure against the budget contract. Unreachable failures ([TransportError.NetworkUnavailable],
 * [TransportError.Timeout] — post-B1 these are connect/pre-send only) never got a server verdict, so they park.
 * An [AuthError] is op-independent (the channel isn't authorized right now) and pre-delivery (401 at the RPC
 * handshake), so it parks too — never dead-letter a queued write on a transient token-refresh blip. A
 * provably-sent-but-lost-response ([TransportError.OutcomeUnknown]) parks on a declared-idempotent channel
 * (safe to re-send) and dead-letters otherwise. A server-answered retryable failure (5xx) or a sanitized
 * escaped-exception fault ([InternalError], usually transient server trouble) burns budget. Everything else
 * non-retryable (4xx, malformed) dead-letters immediately.
 */
private fun classifyFailure(
    error: AppError,
    domainName: String,
): FailureDisposition =
    when {
        error is TransportError.NetworkUnavailable || error is TransportError.Timeout -> {
            FailureDisposition.Parked
        }

        // Op-independent + pre-delivery: the session lapsed/blipped, the op never reached the server.
        // Park so it re-sends the instant the session recovers instead of dead-lettering the edit.
        error is AuthError -> {
            FailureDisposition.Parked
        }

        error is TransportError.OutcomeUnknown -> {
            if (OutboxChannels.isIdempotent(domainName)) FailureDisposition.Parked else FailureDisposition.Terminal
        }

        // A sanitized escaped-exception fault is usually transient (DB lock, restart race), not poison:
        // spend a bounded attempt rather than dead-lettering the user's edit on the first occurrence.
        error is InternalError -> {
            FailureDisposition.Burn
        }

        error.isRetryable -> {
            FailureDisposition.Burn
        }

        else -> {
            FailureDisposition.Terminal
        }
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
 * - [hasQueuedOpFor] is the anti-flicker shield's primitive: a still-dispatchable
 *   op for (domain, entity) means a local edit is in flight, so an inbound echo/
 *   catch-up for that entity is shielded rather than clobbering the optimistic state.
 * - Drain failures classify three ways ([classifyFailure]): a **server-answered
 *   retryable** failure (5xx) increments `failureCount` and dead-letters after
 *   [MAX_RETRYABLE_ATTEMPTS]; a **parked** failure (unreachable, or an idempotent
 *   lost-response) leaves `failureCount` untouched and stays dispatchable so an
 *   outage never silently exhausts the budget; a **non-retryable** failure leaps
 *   past [MAX_RETRYABLE_ATTEMPTS] so it never retries — becoming a **dead letter**.
 *
 * Terminal ops are dead letters: excluded from [observeQueueDepth] (the live,
 * dispatchable count), surfaced separately via [observeDeadLetterCount] and
 * [observeFailedOperations], manually re-armed via [retryOp] or dismissed via
 * [dismissOp], garbage-collected after [DEAD_LETTER_RETENTION_MILLIS] of
 * inactivity, and wiped along with the rest of the queue on user change via
 * [clearForUserChange].
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
     * Tick the enqueue signal so the engine schedules a drain wave. The caller-driven
     * counterpart to [enqueue]'s built-in tick: a transactional enqueue (`signal = false`)
     * defers the tick to its enclosing caller so the signal fires only AFTER the transaction
     * commits — otherwise a drain collector wakes on a signal, reads pre-commit WAL state that
     * doesn't yet see the new row, finds nothing, and strands the op until the next unrelated
     * trigger (see [OfflineEditor.edit]).
     */
    fun signalEnqueued() {
        enqueueCounter.update { it + 1 }
    }

    /**
     * Enqueue a new op on [channel]. Returns its generated `clientOpId`. `check`s [op] is one of
     * [OutboxChannel.ops] — the single validation choke point ensuring a queued op can never drift
     * from what the channel declared.
     *
     * @param coalesce When true, deletes any still-queued (within retry budget) op for the same
     *   (channel, entityId, op) slot before inserting — so rapid successive writes for one
     *   entity collapse to the latest snapshot instead of piling up. Valid only for domains where
     *   the payload is a last-write-wins snapshot of current state (e.g. playback positions);
     *   event/entity-PATCH domains keep the default `false` so every op is replayed. Terminally
     *   failed rows are never coalesced away — see [PendingOperationV2Dao.deleteQueuedOps]. The
     *   delete-then-insert is non-atomic: the one coalescing caller today serializes per entity on
     *   a Mutex, and racing a concurrent drain is benign (Room `@Update`/`@Delete` on an
     *   already-removed row is a silent no-op).
     * @param signal When true (the default), ticks the enqueue signal immediately after inserting
     *   the row. Callers that enqueue INSIDE a write transaction pass `false` and tick via
     *   [signalEnqueued] once the transaction commits — the row write stays inside the transaction
     *   for atomicity, but the drain signal must not fire against pre-commit state where the row is
     *   not yet visible to another connection's reader.
     */
    suspend fun enqueue(
        channel: OutboxChannel<*>,
        entityId: String,
        op: OpKind,
        payload: String,
        ownerUserId: String,
        coalesce: Boolean = false,
        signal: Boolean = true,
    ): String {
        check(op in channel.ops) {
            "op $op is not declared by outbox channel '${channel.name}' (declared: ${channel.ops})"
        }
        if (coalesce) {
            dao.deleteQueuedOps(channel.name, entityId, op.wire)
        }
        val opId = Uuid.random().toString()
        dao.insert(
            PendingOperationV2Entity(
                clientOpId = opId,
                domainName = channel.name,
                entityId = entityId,
                opType = op.wire,
                payload = payload,
                enqueuedAt = nowMillis(),
                lastAttemptAt = null,
                failureCount = 0,
                lastError = null,
                ownerUserId = ownerUserId,
            ),
        )
        if (signal) {
            enqueueCounter.update { it + 1 }
        }
        return opId
    }

    /**
     * True when a still-dispatchable local op is queued for (domainName, entityId).
     *
     * This is the anti-flicker shield's one primitive: entity-level and clientOpId-independent.
     * When an inbound SSE echo or catch-up item arrives for an entity whose local edit is still
     * in flight, the apply is shielded so the optimistic state is not clobbered by a (possibly
     * stale) server snapshot — the authoritative state arrives via the edit's own echo once it
     * drains. Dead-lettered ops do NOT count as in-flight, so a permanently-failed edit lets the
     * entity converge to server truth (see [PendingOperationV2Dao.hasQueuedOp]).
     */
    suspend fun hasQueuedOpFor(
        domainName: String,
        entityId: String,
    ): Boolean = dao.hasQueuedOp(domainName, entityId)

    /**
     * Dispatch the earliest-enqueued op per (domain, entityId) group currently
     * available; each result mutates the row in place (delete on success; on
     * failure, [classifyFailure] decides whether to burn budget, park, or dead-letter).
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
     * Each wave first GCs dead letters older than [DEAD_LETTER_RETENTION_MILLIS]
     * (age-based cleanup of terminally-failed ops), then dispatches — the two sets
     * are disjoint (`failureCount > maxAttempts` vs `<=`), so GC never races dispatch.
     *
     * A [PendingOperationSender] that throws instead of returning
     * [AppResult.Failure] is treated as a transient (retryable) failure — never a
     * reason to abort the wave, and never an instant dead-letter. A raw-proxy
     * transport fault escapes as an untyped throwable; terminally quarantining it
     * on the first throw would permanently lose a queued offline write. The op is
     * retried (bounded by [MAX_RETRYABLE_ATTEMPTS]) and the loop continues.
     */
    suspend fun drain(): DrainOutcome {
        dao.gcDeadLetters(cutoffMillis = nowMillis() - DEAD_LETTER_RETENTION_MILLIS)
        val ops = dao.nextDispatchable()
        var sent = 0
        var retryableFailures = 0
        var parkedFailures = 0
        var terminalFailures = 0
        val sentEntities = mutableListOf<SentEntityRef>()
        for (entity in ops) {
            val op = entity.toDomain()
            val result =
                try {
                    sender.send(op)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A thrown sender exception is a TRANSIENT (retryable) failure, never an instant
                    // terminal dead-letter. A raw-proxy transport fault — a dead RpcClient after the
                    // self-hosted server restarts, a dropped WebSocket — escapes as an untyped throwable
                    // that ErrorMapper misclassifies as non-retryable, permanently losing a queued
                    // position/edit on the first blip (a "never lose the user's position" violation).
                    // Treating a throw as retryable keeps the op queued to retry once the connection
                    // heals (the firehose reconnect invalidates the dead proxy); a genuinely buggy sender
                    // burns the bounded retry budget and then dead-letters — the same bounded-waste
                    // tradeoff a permanently-corrupt payload already accepts.
                    logger.warn(e) { "Sender threw for op ${op.clientOpId} (${op.domainName}); retrying as transient" }
                    dao.update(
                        entity.copy(
                            failureCount = entity.failureCount + 1,
                            lastAttemptAt = nowMillis(),
                            lastError = e::class.simpleName ?: "SenderException",
                        ),
                    )
                    retryableFailures++
                    continue
                }
            when (result) {
                is AppResult.Success -> {
                    dao.delete(op.clientOpId)
                    sent++
                    sentEntities += SentEntityRef(op.domainName, op.entityId)
                }

                is AppResult.Failure -> {
                    val error = result.error
                    when (classifyFailure(error, op.domainName)) {
                        FailureDisposition.Parked -> {
                            // No server verdict (unreachable, or idempotent lost-response): record the
                            // attempt for diagnostics but KEEP failureCount, so an outage can never
                            // silently exhaust an op's budget. It stays dispatchable and re-sends on the
                            // next reachability edge.
                            dao.update(entity.copy(lastAttemptAt = nowMillis(), lastError = error.code))
                            parkedFailures++
                            logger.warn {
                                "Pending op ${op.clientOpId} parked (no server verdict): ${error.code} " +
                                    "(count=${entity.failureCount}, unburned)"
                            }
                        }

                        FailureDisposition.Burn -> {
                            // Server ANSWERED with a retryable failure (5xx): spend one attempt. A
                            // persistently-5xx-ing op quarantines after MAX; a transient one costs ≤MAX.
                            val newCount = entity.failureCount + 1
                            dao.update(
                                entity.copy(
                                    failureCount = newCount,
                                    lastAttemptAt = nowMillis(),
                                    lastError = error.code,
                                ),
                            )
                            retryableFailures++
                            logger.warn {
                                "Pending op ${op.clientOpId} failed (server-answered retryable): ${error.code} (count=$newCount)"
                            }
                        }

                        FailureDisposition.Terminal -> {
                            // Non-retryable: leap past MAX so the SQL filter never picks it up again.
                            dao.update(
                                entity.copy(
                                    failureCount = MAX_RETRYABLE_ATTEMPTS + 1,
                                    lastAttemptAt = nowMillis(),
                                    lastError = error.code,
                                ),
                            )
                            terminalFailures++
                            logger.warn { "Pending op ${op.clientOpId} dead-lettered (non-retryable): ${error.code}" }
                        }
                    }
                }
            }
        }
        return DrainOutcome(
            sent = sent,
            retryableFailures = retryableFailures,
            parkedFailures = parkedFailures,
            terminalFailures = terminalFailures,
            remainingDispatchable = dao.countDispatchable(),
            sentEntities = sentEntities,
        )
    }

    /** Live count of ops still within retry budget. Engine forwards this to `SyncEngineState`. */
    fun observeQueueDepth(): Flow<Int> = dao.observeQueueDepth()

    /** Live count of dead letters (ops past [MAX_RETRYABLE_ATTEMPTS]). Engine forwards to `SyncEngineState`. */
    fun observeDeadLetterCount(): Flow<Int> = dao.observeDeadLetterCount()

    /** Live snapshots of ops still within retry budget, oldest first. */
    fun observePendingOperations(): Flow<List<PendingOperation>> =
        dao.observePending().map { rows -> rows.map { it.toDomain() } }

    /** Live snapshots of terminally failed ops (past [MAX_RETRYABLE_ATTEMPTS]), oldest first. */
    fun observeFailedOperations(): Flow<List<PendingOperation>> =
        dao.observeFailed().map { rows -> rows.map { it.toDomain() } }

    /**
     * Re-arm a terminally failed op and tick the enqueue signal so the engine
     * schedules a drain wave (the same trigger a fresh [enqueue] uses). If the
     * op fails non-retryably again it returns to the terminal state.
     */
    suspend fun retryOp(clientOpId: String) {
        dao.resetFailureCount(clientOpId)
        enqueueCounter.update { it + 1 }
    }

    /**
     * Drop an op permanently. The optimistic local edit stays in Room; it
     * reconciles to server truth on the next catch-up/reconcile pass.
     */
    suspend fun dismissOp(clientOpId: String) {
        dao.delete(clientOpId)
    }

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
            lastError = lastError,
        )
}
