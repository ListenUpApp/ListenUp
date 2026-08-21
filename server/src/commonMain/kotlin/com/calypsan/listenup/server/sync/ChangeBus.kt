package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.logging.loggerFor
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
)

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
class ChangeBus {
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
            PublishSlot(sequence = nextReservedSequence++, entry = BusEvent(repo, event, userId))
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
        resolve(slot.sequence, slot.entry)
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
        resolve(slot.sequence, entry = null)
    }

    /**
     * Marks [sequence] resolved — carrying [entry] to publish, or null when it rolled back — and
     * drains every now-contiguous slot from the head of the queue.
     */
    private fun resolve(
        sequence: Long,
        entry: BusEvent<*>?,
    ) = synchronized(orderLock) {
        resolvedSlots[sequence] = entry
        while (resolvedSlots.containsKey(nextSequenceToPublish)) {
            resolvedSlots.remove(nextSequenceToPublish)?.let { flow.tryEmit(it) }
            nextSequenceToPublish++
        }
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
