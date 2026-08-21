package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

private val log = loggerFor<ChangeBus>()

private const val LIVE_TAIL_BUFFER = 256

/**
 * How many out-of-turn publish slots the sequencer holds before it abandons its stalled head.
 *
 * Only slots that resolved *early* are held, and a slot can only be reserved once its predecessor's
 * transaction has committed (the revision bump holds SQLite's write lock until then), so the real
 * depth is the number of writers inside the post-commit window — single digits. Reaching this bound
 * means a slot was stranded, not that the server is busy.
 *
 * This is the burst trigger; [STALLED_SLOT_TIMEOUT] is the one that matters on a quiet server.
 */
private const val MAX_HELD_SLOTS = 256

/**
 * How long the sequencer waits on an unresolved head before abandoning it.
 *
 * The count bound alone is the wrong trigger for a self-hosted server with one active user: strand
 * the head there and the firehose stays silent until 256 *more* writes arrive, which can be days.
 * Silence hurts most exactly where the counter moves slowest, so elapsed time is the primary
 * trigger and the count is the burst backstop.
 *
 * 30s is three orders of magnitude above the gap this queue actually exists to cover — the window
 * between a COMMIT and its own post-commit hook, which is microseconds — so it cannot fire on a
 * merely slow write, while still healing well inside the span of one user's attention. It is
 * evaluated lazily on the next [resolve], so a stranded slot costs at most one further write's
 * latency rather than a background timer.
 */
private val STALLED_SLOT_TIMEOUT = 30.seconds

/**
 * Type-bound bus entry. The source repository travels alongside the event so
 * the consumer (sync firehose, REST catch-up listener) can encode the event
 * using the repository's own serializer — no static-registry lookup required.
 *
 * The previous untyped shape (`BusEvent(domainName, event)`) relied on a
 * static registry to look up the right serializer at consumption
 * time. That coupling allowed a misrouted publish (wrong domain string, or
 * reflective misuse) to silently encode a payload through the wrong serializer,
 * producing malformed firehose frames and reconnect storms. The typed shape makes
 * the binding compile-checked: a `BusEvent<Tag>` literally cannot carry a
 * `Book` payload.
 */
data class BusEvent<T : Any>(
    val repo: SyncableRepo<T>,
    val event: SyncEvent<T>,
    val userId: String? = null,
)

/**
 * A per-user out-of-band [SyncControl] frame travelling on the bus's control
 * channel, distinct from the data-event channel ([BusEvent]). Carries the target
 * [userId] so the firehose delivers it only to that subscriber.
 *
 * Control frames have no revision and are never replayed on reconnect: they tell a
 * user to re-derive state, so a missed one is recovered the next time the firehose
 * delivers one or the client re-pulls. Keeping them off the revision-cursored data
 * channel avoids polluting `Last-Event-Id` resume semantics.
 */
data class ControlFrame(
    val control: SyncControl,
    val userId: String,
)

/**
 * A reserved position in the live tail's publish order.
 *
 * Handed out by [ChangeBus.reserve] from **inside** the writing transaction — immediately after
 * that transaction bumped the global revision counter, so it still holds SQLite's write lock and
 * no other writer can have taken a revision in between. Reservation order is therefore revision
 * order, by construction.
 *
 * The slot is resolved exactly once, after the transaction ends: [ChangeBus.release] on commit,
 * [ChangeBus.discard] on rollback. Until then it holds its place, and any later slot that resolves
 * first waits behind it.
 */
class PublishSlot internal constructor(
    internal val sequence: Long,
    internal val entry: BusEvent<*>,
) {
    /**
     * Whether this slot has already been resolved, guarded by `ChangeBus.orderLock`.
     *
     * A slot must resolve exactly once. It can be offered twice: [ChangeBus.discardReservedSince]
     * drops the slots of a rolled-back savepoint, and SQLDelight then hands that savepoint's
     * `afterCommit` hook up to the enclosing transaction, which runs it anyway on the parent's
     * commit. The second offer has to be ignored — re-inserting a sequence the queue has already
     * drained past would strand it below the head forever and can drag the valve's resume point
     * backwards.
     */
    internal var resolved: Boolean = false
}

/**
 * In-memory pub/sub for [BusEvent]s. Single bus per process, registered as a
 * Koin singleton with `createdAtStart()` so domain repositories' init blocks
 * can publish during application bootstrap.
 *
 * `replay = 256` retains the last 256 events for late subscribers and for
 * [oldestRetainedRevision] reads. `extraBufferCapacity = 0` means the replay
 * cache is the sole buffer; `BufferOverflow.DROP_OLDEST` evicts the head of
 * the replay cache when it is full, so a slow client gets
 * `SyncControl.CursorStale` and falls back to REST catch-up.
 *
 * [oldestRetainedRevision] reads directly from [MutableSharedFlow.replayCache]
 * so it always reflects the actual buffer floor, including after DROP_OLDEST
 * evictions.
 */
class ChangeBus(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val stalledSlotTimeout: Duration = STALLED_SLOT_TIMEOUT,
) {
    private val flow =
        MutableSharedFlow<BusEvent<*>>(
            replay = LIVE_TAIL_BUFFER,
            extraBufferCapacity = 0,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    // Control frames ride a separate, non-replayed channel: a re-derive nudge is
    // transient and cursor-free, so it must not enter the revision-cursored replay
    // buffer that drives Last-Event-Id resume. extraBufferCapacity keeps a slow
    // subscriber from blocking the publisher; DROP_OLDEST is harmless here because a
    // dropped nudge is superseded by the next one (or recovered by a client re-pull).
    private val controlFlow =
        MutableSharedFlow<ControlFrame>(
            replay = 0,
            extraBufferCapacity = LIVE_TAIL_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    // Publish-order sequencer (see [reserve]). `nextReservedSequence` numbers slots as writers take
    // them under SQLite's write lock; `nextSequenceToPublish` is the head of the queue, and
    // `resolvedSlots` holds slots that finished out of turn until their predecessors catch up. The
    // map stays tiny — a slot can only be reserved after its predecessor's transaction committed,
    // so at most a handful of post-commit hooks are ever in flight at once.
    private val orderLock = SynchronizedObject()
    private var nextReservedSequence = 0L
    private var nextSequenceToPublish = 0L
    private val resolvedSlots = mutableMapOf<Long, BusEvent<*>?>()

    // Reserved-but-unresolved slots, so [discardReservedSince] can drop exactly the ones a
    // rolled-back savepoint took. Entries leave as their slot resolves or is abandoned.
    private val outstanding = mutableMapOf<Long, PublishSlot>()

    // When each still-unresolved slot was reserved, so a stalled head can be aged out by
    // [STALLED_SLOT_TIMEOUT]. Entries are dropped as their slot resolves (or is abandoned), so this
    // holds only what is genuinely in flight.
    private val reservedAt = mutableMapOf<Long, TimeMark>()

    /**
     * Publishes [event] onto the bus, paired with the source [repo] so consumers
     * can encode the payload through the repo's own serializer. The `<T>` binding
     * statically prevents publishing an event whose payload type doesn't match
     * the repo's element type.
     */
    suspend fun <T : Any> publish(
        repo: SyncableRepo<T>,
        event: SyncEvent<T>,
        userId: String? = null,
    ) {
        log.debug { "change published: domain=${repo.domainName} event=${event::class.simpleName} id=${event.id}" }
        emitOrDefer { flow.tryEmit(BusEvent(repo, event, userId)) }
    }

    /**
     * Reserves this write's position in the live tail, ahead of its own commit.
     *
     * A committed write cannot simply emit from its `afterCommit` hook and expect the arrival
     * order to match revision order. SQLite serializes the revision bump under the write lock,
     * but that lock is released by the COMMIT itself — so between one writer's COMMIT and its
     * `afterCommit` hook actually running, a later writer can take the next revision, commit, and
     * emit first. Revision 6 then lands ahead of revision 5, and a client that assigns its resume
     * cursor from the arriving frame skips 5 for good.
     *
     * Reserving here closes that window: the slot is taken while the write lock is still held, so
     * slots are numbered in revision order, and [release] only ever emits from the head of that
     * queue. Callers pair this with [release]/[discard] through
     * [emitInPublishOrder][com.calypsan.listenup.server.sync.emitInPublishOrder] rather than by
     * hand — that seam is the only supported way onto the post-commit path.
     */
    fun <T : Any> reserve(
        repo: SyncableRepo<T>,
        event: SyncEvent<T>,
        userId: String? = null,
    ): PublishSlot =
        synchronized(orderLock) {
            val sequence = nextReservedSequence++
            reservedAt[sequence] = timeSource.markNow()
            PublishSlot(sequence = sequence, entry = BusEvent(repo, event, userId))
                .also { outstanding[sequence] = it }
        }

    /**
     * Publishes the write behind [slot], once its transaction has committed.
     *
     * The slot's event is emitted only when every earlier slot has resolved, so the live tail
     * stays in revision order however the post-commit hooks are scheduled. A slot that resolves
     * out of turn is held until its predecessors drain, then flushed with them — inside the lock,
     * so two threads draining concurrently cannot interleave their emits.
     *
     * `tryEmit` (not `emit`) matches [publish]: with `replay = LIVE_TAIL_BUFFER` + `DROP_OLDEST`
     * it always succeeds and never suspends the post-commit callback.
     */
    fun release(slot: PublishSlot) {
        log.debug {
            val event = slot.entry.event
            "change emitted post-commit: domain=${slot.entry.repo.domainName} " +
                "event=${event::class.simpleName} id=${event.id}"
        }
        resolveOnce(slot, slot.entry)
    }

    /**
     * Drops [slot] without publishing, once its transaction has rolled back.
     *
     * The rolled-back write's revision is burned and its row never existed, so there is nothing to
     * announce — but the slot still has to be resolved, or every later write would queue behind it
     * forever. This is the `afterRollback` half of the pair [release] completes on commit.
     */
    fun discard(slot: PublishSlot) {
        log.debug { "change discarded (rolled back): domain=${slot.entry.repo.domainName} id=${slot.entry.event.id}" }
        resolveOnce(slot, entry = null)
    }

    /**
     * The publish-order position the next [reserve] will take — the caller's "everything from here"
     * marker, paired with [discardReservedSince].
     */
    fun mark(): Long = synchronized(orderLock) { nextReservedSequence }

    /**
     * Drops every still-unresolved slot reserved at or after [mark], without publishing any of them.
     *
     * This is what a **rolled-back savepoint** needs, and SQLDelight cannot provide it. A nested
     * transaction hands BOTH its hook lists to its parent unconditionally (`postTransactionCleanup`,
     * nested branch), so a savepoint that rolls back inside a parent that goes on to commit still has
     * its `afterCommit` hook run — announcing a write whose row was rolled away. Only the site that
     * caught the failure knows the savepoint died, so it tells the bus here; the later transferred
     * `release` is ignored because the slot is already resolved.
     *
     * Safe against a range because slots are reserved under SQLite's write lock, held for the whole
     * enclosing transaction: no other writer can have taken a slot in between, so everything at or
     * after [mark] belongs to the caller.
     */
    fun discardReservedSince(mark: Long) =
        synchronized(orderLock) {
            outstanding.values
                .filter { it.sequence >= mark && !it.resolved }
                .sortedBy { it.sequence }
                .forEach { slot ->
                    log.debug {
                        "change discarded (savepoint rolled back): " +
                            "domain=${slot.entry.repo.domainName} id=${slot.entry.event.id}"
                    }
                    resolveLocked(slot, entry = null)
                }
        }

    /**
     * Marks [sequence] resolved — carrying [entry] to publish, or null when it rolled back — and
     * drains every now-contiguous slot from the head of the queue.
     */
    private fun resolveOnce(
        slot: PublishSlot,
        entry: BusEvent<*>?,
    ) = synchronized(orderLock) {
        if (slot.resolved) return@synchronized
        resolveLocked(slot, entry)
    }

    /** [resolveOnce]'s body, for callers that already hold [orderLock] and have checked the flag. */
    private fun resolveLocked(
        slot: PublishSlot,
        entry: BusEvent<*>?,
    ) {
        slot.resolved = true
        outstanding.remove(slot.sequence)
        resolvedSlots[slot.sequence] = entry
        reservedAt.remove(slot.sequence)
        abandonStalledHeadIfNeeded()
        while (resolvedSlots.containsKey(nextSequenceToPublish)) {
            resolvedSlots.remove(nextSequenceToPublish)?.let { flow.tryEmit(it) }
            reservedAt.remove(nextSequenceToPublish)
            nextSequenceToPublish++
        }
    }

    /**
     * Liveness valve: gives up on a head slot that is never going to resolve.
     *
     * SQLDelight resolves a slot exactly once through the hooks [emitInPublishOrder] registers —
     * but it invokes post-commit hooks with a bare `forEach`, so a hook that throws skips the rest
     * of the list, and it calls `endTransaction()` from inside a `finally` whose result is
     * discarded if the COMMIT itself throws, in which case no hook list runs at all. Either path
     * strands a slot. Before this queue existed those paths caused *reordering*; with a queue in
     * front of them they would instead cause *silence* — the entire firehose, every domain, every
     * user, for the life of the process. That is a strictly worse failure than the one the queue
     * repairs, which is what makes this valve load-bearing rather than defensive padding.
     *
     * Fires on elapsed time or held count, whichever comes first, and only ever abandons the run of
     * unresolved slots below the lowest slot that *has* resolved — one jump, not one-at-a-time. If
     * the slot after the drained run is also unresolved the queue simply re-blocks there, and that
     * new head is aged independently on a later [resolve]: at most one abandonment per call, never
     * a cascade that drains the whole queue in one pass, and no spin (the drain loop is bounded by
     * the map, removing an entry per iteration).
     */
    private fun abandonStalledHeadIfNeeded() {
        if (resolvedSlots.isEmpty() || resolvedSlots.containsKey(nextSequenceToPublish)) return
        val heldFor = reservedAt[nextSequenceToPublish]?.elapsedNow()
        val timedOut = heldFor != null && heldFor >= stalledSlotTimeout
        if (!timedOut && resolvedSlots.size <= MAX_HELD_SLOTS) return

        val abandoned = nextSequenceToPublish
        val resumeAt = resolvedSlots.keys.min()
        // A silent self-healing valve hides the defect it compensates for — this must be greppable.
        log.warn {
            "publish slot $abandoned never resolved (held ${heldFor ?: "unknown"}, " +
                "${resolvedSlots.size} writes queued behind it); abandoning slots $abandoned..${resumeAt - 1}"
        }
        var skipped = abandoned
        while (skipped < resumeAt) {
            reservedAt.remove(skipped)
            // Retire the slot too, so a late `release` for an abandoned sequence is ignored rather
            // than re-inserting it below the head where nothing would ever drain it.
            outstanding.remove(skipped)?.resolved = true
            skipped++
        }
        nextSequenceToPublish = resumeAt
    }

    fun subscribe(): SharedFlow<BusEvent<*>> = flow.asSharedFlow()

    /**
     * Publishes a per-user [control] frame onto the control channel, addressed to
     * [userId]. The firehose delivers it only to that user's subscriber(s).
     */
    suspend fun publishControl(
        control: SyncControl,
        userId: String,
    ) {
        log.debug { "control published: type=${control::class.simpleName} userId=$userId" }
        emitOrDefer { controlFlow.tryEmit(ControlFrame(control, userId)) }
    }

    /**
     * Publishes a [control] frame to EVERY connected subscriber, addressed to the
     * [BROADCAST] sentinel userId. The firehose delivers it to all subscribers
     * regardless of their own userId. Use for content-free nudges only — a
     * broadcast frame carries no per-user or per-resource data, so it cannot leak.
     */
    suspend fun broadcastControl(control: SyncControl) {
        log.debug { "control broadcast: type=${control::class.simpleName}" }
        emitOrDefer { controlFlow.tryEmit(ControlFrame(control, BROADCAST)) }
    }

    fun subscribeControl(): SharedFlow<ControlFrame> = controlFlow.asSharedFlow()

    /**
     * Live subscriber count on the control channel. The control channel has no replay, so a
     * frame published before a collector attaches is silently lost — awaiting
     * `first { it > 0 }` here is the deterministic attach barrier tests use before publishing
     * the frame under assertion (no sleep-based races).
     */
    val controlSubscriptionCount: StateFlow<Int> get() = controlFlow.subscriptionCount

    /**
     * Runs [emit] immediately. The only callers ([publish], [publishControl],
     * [broadcastControl]) fire outside any storage transaction, so there is nothing to defer
     * against — the data write the event refers to is already durable by the time the emit runs.
     * `tryEmit` (not `emit`) is used because, with `replay = LIVE_TAIL_BUFFER` + `DROP_OLDEST`,
     * it always succeeds and never suspends the caller.
     *
     * Writes that DO need post-commit deferral (the SQLDelight aggregate repositories) never
     * route through here: [SqlSyncableRepository] registers its live-tail emit as a SQLDelight
     * `afterCommit` hook and calls [emit] directly once the JDBC commit has happened. That is the
     * live deferral mechanism; this bus method is purely the immediate path.
     */
    private fun emitOrDefer(emit: () -> Unit) = emit()

    companion object {
        /** Sentinel [ControlFrame.userId] marking a frame destined for every subscriber. */
        const val BROADCAST = "*"
    }

    /**
     * Best-effort lower bound on the oldest revision still in the live-tail
     * replay buffer. Returns null when the buffer is empty (no events
     * published since process start, or all events evicted under DROP_OLDEST).
     */
    fun oldestRetainedRevision(): Long? =
        flow.replayCache
            .firstOrNull()
            ?.event
            ?.revision
}
